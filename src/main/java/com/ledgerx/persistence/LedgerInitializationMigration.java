package com.ledgerx.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Deterministic, read-mostly V006 classifier; it never changes historical rows. */
public final class LedgerInitializationMigration implements MigrationRunner.MigrationHook {
    public static final String DEFAULT_ACCOUNT_ID = "f3a0c2ec-6b64-48c7-9f7f-0b604e4d8901";
    private static final String HOOK_VERSION = "p7-002-ledger-initialization-classifier-v1";
    private static final Set<String> RECORD_TYPES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "INCOME", "FIXED_COST", "VARIABLE_COST", "FIXED_ASSET_PURCHASE", "PAYABLE_CREATED", "PAYABLE_PAYMENT")));
    private static final Set<String> SETTLEMENT_MODES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "PAID_FROM_ACCOUNT", "INCLUDED_IN_OPENING_BALANCE", "NON_CASH", "CREATE_PAYABLE")));

    private final boolean newLedger;
    private final Clock clock;

    public LedgerInitializationMigration(boolean newLedger, Clock clock) {
        this.newLedger = newLedger;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    @Override
    public String checksumToken() {
        return HOOK_VERSION;
    }

    @Override
    public void apply(Connection connection) throws SQLException, PersistenceException {
        List<AccountData> accounts = readAccounts(connection);
        AccountData defaultAccount = null;
        for (AccountData account : accounts) {
            if (DEFAULT_ACCOUNT_ID.equals(account.id)) defaultAccount = account;
        }
        if (defaultAccount == null) {
            throw new PersistenceException("default system account is missing during ledger initialization migration");
        }

        List<RecordData> records = readRecords(connection);
        Map<String, String> earliestPaidSettlement = new HashMap<>();
        Set<String> referencedAccounts = new HashSet<>();
        LocalDate earliestObservedDate = null;
        for (AccountData account : accounts) {
            account.openingOn = strictDate(account.openingOnText, "account opening date");
            earliestObservedDate = min(earliestObservedDate, account.openingOn);
        }

        for (RecordData record : records) {
            record.occurredOn = strictDate(record.occurredOnText, "record occurrence date");
            earliestObservedDate = min(earliestObservedDate, record.occurredOn);
            if (record.settlementOnText != null) {
                record.settlementOn = strictDate(record.settlementOnText, "record settlement date");
                earliestObservedDate = min(earliestObservedDate, record.settlementOn);
            }
            if (!RECORD_TYPES.contains(record.recordType) || !SETTLEMENT_MODES.contains(record.settlementMode)) {
                throw new PersistenceException("ledger contains an unsupported historical record value");
            }
            if ("PAID_FROM_ACCOUNT".equals(record.settlementMode)) {
                if (record.accountId == null || record.settlementOn == null) {
                    throw new PersistenceException("paid historical record is missing its account or settlement date");
                }
                AccountData account = findAccount(accounts, record.accountId);
                if (account == null) {
                    throw new PersistenceException("paid historical record references a missing account");
                }
                referencedAccounts.add(account.id);
                String current = earliestPaidSettlement.get(account.id);
                if (current == null || record.settlementOnText.compareTo(current) < 0) {
                    earliestPaidSettlement.put(account.id, record.settlementOnText);
                }
            }
        }

        boolean untouchedUnreferencedSeed = isOriginalSeed(defaultAccount) && !referencedAccounts.contains(defaultAccount.id);
        if (untouchedUnreferencedSeed) {
            earliestObservedDate = null;
            for (AccountData account : accounts) {
                if (!DEFAULT_ACCOUNT_ID.equals(account.id)) earliestObservedDate = min(earliestObservedDate, account.openingOn);
            }
            for (RecordData record : records) {
                earliestObservedDate = min(earliestObservedDate, record.occurredOn);
                if (record.settlementOn != null) earliestObservedDate = min(earliestObservedDate, record.settlementOn);
            }
        }

        String state;
        LocalDate ledgerStartOn = null;
        Instant completedAt = null;
        if (newLedger) {
            state = "PENDING";
        } else if (records.isEmpty()) {
            state = accounts.size() == 1 && untouchedUnreferencedSeed ? "PENDING" : "REVIEW_REQUIRED";
        } else if (!hasOpeningDateConflict(earliestPaidSettlement, accounts)) {
            if (earliestObservedDate == null) {
                throw new PersistenceException("ledger with history has no observable business date");
            }
            state = "COMPLETED";
            ledgerStartOn = earliestObservedDate;
            completedAt = Instant.now(clock);
        } else {
            state = "REVIEW_REQUIRED";
        }
        writeSetupState(connection, state, ledgerStartOn, completedAt);
    }

    private static List<AccountData> readAccounts(Connection connection) throws SQLException, PersistenceException {
        List<AccountData> accounts = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id,name,kind,balance_side,opening_on,opening_balance_minor,include_in_available_cash,"
                        + "is_system,archived_at,revision FROM financial_account ORDER BY id");
                ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                accounts.add(new AccountData(rows.getString("id"), rows.getString("name"), rows.getString("kind"),
                        rows.getString("balance_side"), rows.getString("opening_on"), rows.getLong("opening_balance_minor"),
                        rows.getInt("include_in_available_cash"), rows.getInt("is_system"),
                        rows.getString("archived_at"), rows.getLong("revision")));
            }
        }
        return accounts;
    }

    private static List<RecordData> readRecords(Connection connection) throws SQLException {
        List<RecordData> records = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id,record_type,occurred_on,account_id,settlement_mode,settlement_on FROM finance_record ORDER BY id");
                ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                records.add(new RecordData(rows.getString("id"), rows.getString("record_type"),
                        rows.getString("occurred_on"), rows.getString("account_id"),
                        rows.getString("settlement_mode"), rows.getString("settlement_on")));
            }
        }
        return records;
    }

    private static boolean isOriginalSeed(AccountData account) {
        return "现金储备".equals(account.name) && "CASH".equals(account.kind) && "ASSET".equals(account.balanceSide)
                && account.openingBalanceMinor == 0L && account.includeInAvailableCash == 1
                && account.system == 1 && account.archivedAt == null && account.revision == 0L;
    }

    private static boolean hasOpeningDateConflict(Map<String, String> earliest, List<AccountData> accounts)
            throws PersistenceException {
        for (AccountData account : accounts) {
            String settlement = earliest.get(account.id);
            if (settlement != null && settlement.compareTo(account.openingOnText) < 0) return true;
        }
        return false;
    }

    private static AccountData findAccount(List<AccountData> accounts, String id) {
        for (AccountData account : accounts) if (account.id.equals(id)) return account;
        return null;
    }

    private static LocalDate strictDate(String value, String description) throws PersistenceException {
        try {
            LocalDate parsed = LocalDate.parse(value);
            if (!parsed.toString().equals(value)) throw new IllegalArgumentException();
            return parsed;
        } catch (RuntimeException ex) {
            throw new PersistenceException(description + " is not a valid ISO date", ex);
        }
    }

    private static LocalDate min(LocalDate current, LocalDate candidate) {
        return current == null || candidate.isBefore(current) ? candidate : current;
    }

    private static void writeSetupState(Connection connection, String state, LocalDate ledgerStartOn,
            Instant completedAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE ledger_setting SET setup_state=?,ledger_start_on=?,setup_completed_at=? WHERE id=1")) {
            statement.setString(1, state);
            statement.setString(2, ledgerStartOn == null ? null : ledgerStartOn.toString());
            statement.setString(3, completedAt == null ? null : completedAt.toString());
            if (statement.executeUpdate() != 1) throw new SQLException("ledger settings row is missing");
        }
    }

    private static final class AccountData {
        private final String id;
        private final String name;
        private final String kind;
        private final String balanceSide;
        private final String openingOnText;
        private final long openingBalanceMinor;
        private final int includeInAvailableCash;
        private final int system;
        private final String archivedAt;
        private final long revision;
        private LocalDate openingOn;

        private AccountData(String id, String name, String kind, String balanceSide, String openingOnText,
                long openingBalanceMinor, int includeInAvailableCash, int system, String archivedAt, long revision) {
            this.id = id; this.name = name; this.kind = kind; this.balanceSide = balanceSide;
            this.openingOnText = openingOnText; this.openingBalanceMinor = openingBalanceMinor;
            this.includeInAvailableCash = includeInAvailableCash; this.system = system;
            this.archivedAt = archivedAt; this.revision = revision;
        }
    }

    private static final class RecordData {
        private final String id;
        private final String recordType;
        private final String occurredOnText;
        private final String accountId;
        private final String settlementMode;
        private final String settlementOnText;
        private LocalDate occurredOn;
        private LocalDate settlementOn;

        private RecordData(String id, String recordType, String occurredOnText, String accountId,
                String settlementMode, String settlementOnText) {
            this.id = id; this.recordType = recordType; this.occurredOnText = occurredOnText;
            this.accountId = accountId; this.settlementMode = settlementMode; this.settlementOnText = settlementOnText;
        }
    }
}
