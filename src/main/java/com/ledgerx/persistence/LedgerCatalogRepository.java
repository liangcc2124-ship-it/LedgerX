package com.ledgerx.persistence;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Collections;
import com.ledgerx.application.ledger.RecordQuery;

/** SQL boundary for the active ledger catalog and its record references. */
public final class LedgerCatalogRepository {
    private final Path ledgerFile;

    public LedgerCatalogRepository(Path ledgerFile) {
        if (ledgerFile == null) throw new IllegalArgumentException("ledgerFile is required");
        this.ledgerFile = ledgerFile.toAbsolutePath().normalize();
    }

    public Connection openConnection() throws PersistenceException {
        try {
            return new SqliteDatabase(ledgerFile).open();
        } catch (IOException | SQLException ex) {
            throw new PersistenceException("ledger database could not be opened", ex);
        }
    }

    public List<CategoryRecord> listCategories(Connection c, boolean includeArchived) throws SQLException {
        String sql = "SELECT id,parent_id,name,is_system,is_legacy_custom,archived_at,sort_order," +
                "default_recognition_method,recommended_depreciation_method,created_at,updated_at,revision " +
                "FROM category WHERE (? = 1 OR archived_at IS NULL) " +
                "ORDER BY CASE WHEN archived_at IS NULL THEN 0 ELSE 1 END, " +
                "CASE WHEN parent_id IS NULL THEN 0 ELSE 1 END, sort_order, name COLLATE NOCASE, id";
        List<CategoryRecord> result = new ArrayList<>();
        try (PreparedStatement s = c.prepareStatement(sql)) {
            s.setInt(1, includeArchived ? 1 : 0);
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) result.add(readCategory(c, r));
            }
        }
        return result;
    }

    public CategoryRecord findCategory(Connection c, String id) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("SELECT id,parent_id,name,is_system,is_legacy_custom,archived_at,"
                + "sort_order,default_recognition_method,recommended_depreciation_method,created_at,updated_at,revision "
                + "FROM category WHERE id = ?")) {
            s.setString(1, id);
            try (ResultSet r = s.executeQuery()) { return r.next() ? readCategory(c, r) : null; }
        }
    }

    public void insertCategory(Connection c, CategoryRecord category, String now) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("INSERT INTO category "
                + "(id,parent_id,name,is_system,is_legacy_custom,sort_order,default_recognition_method,"
                + "created_at,updated_at,revision) VALUES (?,?,?,0,0,?,'IMMEDIATE',?,?,0)")) {
            s.setString(1, category.id); s.setString(2, category.parentId); s.setString(3, category.name);
            s.setInt(4, category.sortOrder); s.setString(5, now); s.setString(6, now); s.executeUpdate();
        }
    }

    public void updateCategory(Connection c, String id, String name, String parentId, int sortOrder,
            long revision, String now) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("UPDATE category SET name=?,parent_id=?,sort_order=?,"
                + "updated_at=?,revision=? WHERE id=?")) {
            s.setString(1, name); s.setString(2, parentId); s.setInt(3, sortOrder); s.setString(4, now);
            s.setLong(5, revision); s.setString(6, id); s.executeUpdate();
        }
    }

    public void archiveCategory(Connection c, String id, long revision, String now) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("UPDATE category SET archived_at=?,updated_at=?,revision=? WHERE id=?")) {
            s.setString(1, now); s.setString(2, now); s.setLong(3, revision); s.setString(4, id); s.executeUpdate();
        }
    }

    public int maxSortOrder(Connection c, String parentId) throws SQLException {
        String sql = parentId == null ? "SELECT COALESCE(MAX(sort_order),-1) FROM category WHERE parent_id IS NULL"
                : "SELECT COALESCE(MAX(sort_order),-1) FROM category WHERE parent_id = ?";
        try (PreparedStatement s = c.prepareStatement(sql)) {
            if (parentId != null) s.setString(1, parentId);
            try (ResultSet r = s.executeQuery()) { r.next(); return r.getInt(1); }
        }
    }

    public int activeChildCount(Connection c, String parentId) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("SELECT COUNT(*) FROM category WHERE parent_id=? AND archived_at IS NULL")) {
            s.setString(1, parentId); try (ResultSet r = s.executeQuery()) { r.next(); return r.getInt(1); }
        }
    }

    public List<String> recordTypes(Connection c, String id) throws SQLException {
        List<String> result = new ArrayList<>();
        try (PreparedStatement s = c.prepareStatement("SELECT record_type FROM category_record_type WHERE category_id=? ORDER BY record_type")) {
            s.setString(1, id); try (ResultSet r = s.executeQuery()) { while (r.next()) result.add(r.getString(1)); }
        }
        return result;
    }

    public void mergeRecords(Connection c, String targetId, String[] sourceIds, String now) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("UPDATE finance_record SET category_id=?,updated_at=?,revision=revision+1 WHERE category_id=?")) {
            for (String source : sourceIds) { s.setString(1, targetId); s.setString(2, now); s.setString(3, source); s.addBatch(); }
            s.executeBatch();
        }
    }

    public int recordCount(Connection c, String sourceId) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("SELECT COUNT(*) FROM finance_record WHERE category_id=?")) {
            s.setString(1, sourceId); try (ResultSet r=s.executeQuery()){r.next(); return r.getInt(1);}
        }
    }

    public List<AccountRecord> listAccounts(Connection c, boolean includeArchived, LocalDate asOf) throws SQLException {
        String sql = "SELECT id,name,kind,balance_side,opening_on,opening_balance_minor,include_in_available_cash,"
                + "is_system,archived_at,created_at,updated_at,revision FROM financial_account "
                + "WHERE (? = 1 OR archived_at IS NULL) ORDER BY CASE WHEN archived_at IS NULL THEN 0 ELSE 1 END, name COLLATE NOCASE, id";
        List<AccountRecord> result = new ArrayList<>();
        try (PreparedStatement s = c.prepareStatement(sql)) {
            s.setInt(1, includeArchived ? 1 : 0);
            try (ResultSet r = s.executeQuery()) { while (r.next()) result.add(readAccount(c, r, asOf)); }
        }
        return result;
    }

    public AccountRecord findAccount(Connection c, String id, LocalDate asOf) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("SELECT id,name,kind,balance_side,opening_on,opening_balance_minor,"
                + "include_in_available_cash,is_system,archived_at,created_at,updated_at,revision FROM financial_account WHERE id=?")) {
            s.setString(1,id); try(ResultSet r=s.executeQuery()){return r.next()?readAccount(c,r,asOf):null;}
        }
    }

    public void insertAccount(Connection c, AccountRecord a, String now) throws SQLException {
        try (PreparedStatement s=c.prepareStatement("INSERT INTO financial_account(id,name,kind,balance_side,opening_on,opening_balance_minor,include_in_available_cash,is_system,archived_at,created_at,updated_at,revision) VALUES(?,?,?,?,?,?,?,0,NULL,?,?,0)")) {
            s.setString(1,a.id);s.setString(2,a.name);s.setString(3,a.kind);s.setString(4,a.balanceSide);s.setString(5,a.openingOn.toString());s.setLong(6,a.openingBalanceMinor);s.setInt(7,a.includeInAvailableCash?1:0);s.setString(8,now);s.setString(9,now);s.executeUpdate();
        }
    }

    public void updateAccount(Connection c, String id, AccountRecord a, long revision, String now) throws SQLException {
        try (PreparedStatement s=c.prepareStatement("UPDATE financial_account SET name=?,kind=?,balance_side=?,opening_on=?,opening_balance_minor=?,include_in_available_cash=?,updated_at=?,revision=? WHERE id=?")) {
            s.setString(1,a.name);s.setString(2,a.kind);s.setString(3,a.balanceSide);s.setString(4,a.openingOn.toString());s.setLong(5,a.openingBalanceMinor);s.setInt(6,a.includeInAvailableCash?1:0);s.setString(7,now);s.setLong(8,revision);s.setString(9,id);s.executeUpdate();
        }
    }

    public void archiveAccount(Connection c,String id,long revision,String now) throws SQLException {
        try(PreparedStatement s=c.prepareStatement("UPDATE financial_account SET archived_at=?,updated_at=?,revision=? WHERE id=?")){s.setString(1,now);s.setString(2,now);s.setLong(3,revision);s.setString(4,id);s.executeUpdate();}
    }

    public String earliestSettlement(Connection c,String accountId) throws SQLException {
        try(PreparedStatement s=c.prepareStatement("SELECT MIN(settlement_on) FROM finance_record WHERE account_id=? AND deleted_at IS NULL AND settlement_mode='PAID_FROM_ACCOUNT'")){s.setString(1,accountId);try(ResultSet r=s.executeQuery()){return r.next()?r.getString(1):null;}}
    }

    public List<RecordRecord> listRecords(Connection c, String status) throws SQLException {
        return listRecords(c, RecordQuery.basic(status, 200, null));
    }

    public List<RecordRecord> listRecords(Connection c, RecordQuery query) throws SQLException {
        String deleted = "TRASHED".equals(query.getStatus()) ? "IS NOT NULL" : "IS NULL";
        String sql = "SELECT r.id,r.occurred_on,r.record_type,r.amount_minor,r.category_id,r.account_id,"
                + "r.settlement_mode,r.settlement_on,r.note,r.created_at,r.updated_at,r.deleted_at,r.revision,"
                + "c.name AS category_name,c.archived_at AS category_archived,a.name AS account_name,a.archived_at AS account_archived "
                + "FROM finance_record r LEFT JOIN category c ON c.id=r.category_id LEFT JOIN financial_account a ON a.id=r.account_id "
                + "WHERE r.deleted_at " + deleted + " AND r.record_type IN ('INCOME','FIXED_COST','VARIABLE_COST') ";
        List<Object> params = new ArrayList<>();
        if (!query.getRecordTypes().isEmpty()) {
            sql += "AND r.record_type IN (" + placeholders(query.getRecordTypes().size()) + ") ";
            params.addAll(query.getRecordTypes());
        }
        if (query.getOccurredFrom() != null) { sql += "AND r.occurred_on >= ? "; params.add(query.getOccurredFrom().toString()); }
        if (query.getOccurredToExclusive() != null) { sql += "AND r.occurred_on < ? "; params.add(query.getOccurredToExclusive().toString()); }
        if (!query.getCategoryIds().isEmpty()) { sql += "AND r.category_id IN (" + placeholders(query.getCategoryIds().size()) + ") "; params.addAll(query.getCategoryIds()); }
        if (!query.getAccountIds().isEmpty()) { sql += "AND r.account_id IN (" + placeholders(query.getAccountIds().size()) + ") "; params.addAll(query.getAccountIds()); }
        if (query.getQuery() != null) {
            sql += "AND (LOWER(COALESCE(c.name,'') || ' ' || COALESCE(r.note,'')) LIKE ? ESCAPE '\\') ";
            params.add("%" + escapeLike(query.getQuery().toLowerCase()) + "%");
        }
        if (query.getAmountMinMinor() != null) { sql += "AND r.amount_minor >= ? "; params.add(query.getAmountMinMinor()); }
        if (query.getAmountMaxMinor() != null) { sql += "AND r.amount_minor <= ? "; params.add(query.getAmountMaxMinor()); }
        sql += "ORDER BY r.occurred_on DESC,r.id DESC";
        List<RecordRecord> result = new ArrayList<>();
        try (PreparedStatement s=c.prepareStatement(sql)) {
            for (int i = 0; i < params.size(); i++) {
                Object param = params.get(i);
                if (param instanceof Long) s.setLong(i + 1, (Long) param); else s.setString(i + 1, param.toString());
            }
            try (ResultSet r=s.executeQuery()) {
            while (r.next()) result.add(readRecord(r));
            }
        }
        return result;
    }

    private static String placeholders(int count) { return String.join(",", Collections.nCopies(count, "?")); }
    private static String escapeLike(String value) { return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_"); }

    public RecordRecord findRecord(Connection c, String id) throws SQLException {
        String sql = "SELECT r.id,r.occurred_on,r.record_type,r.amount_minor,r.category_id,r.account_id,"
                + "r.settlement_mode,r.settlement_on,r.note,r.created_at,r.updated_at,r.deleted_at,r.revision,"
                + "c.name AS category_name,c.archived_at AS category_archived,a.name AS account_name,a.archived_at AS account_archived "
                + "FROM finance_record r LEFT JOIN category c ON c.id=r.category_id LEFT JOIN financial_account a ON a.id=r.account_id WHERE r.id=?";
        try (PreparedStatement s=c.prepareStatement(sql)) { s.setString(1,id); try(ResultSet r=s.executeQuery()){return r.next()?readRecord(r):null;} }
    }

    public void insertRecord(Connection c, RecordRecord r, String now) throws SQLException {
        try (PreparedStatement s=c.prepareStatement("INSERT INTO finance_record(id,occurred_on,record_type,amount_minor,currency_code,category_id,account_id,settlement_mode,settlement_on,income_source,is_self_generated_income,is_non_essential,note,created_at,updated_at,deleted_at,revision) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            s.setString(1,r.id); s.setString(2,r.occurredOn); s.setString(3,r.recordType); s.setLong(4,r.amountMinor); s.setString(5,"CNY"); s.setString(6,r.categoryId);
            s.setString(7,r.accountId); s.setString(8,"PAID_FROM_ACCOUNT"); s.setString(9,r.settlementOn); s.setNull(10,java.sql.Types.VARCHAR); s.setInt(11,0); s.setInt(12,0); s.setString(13,r.note); s.setString(14,now); s.setString(15,now); s.setNull(16,java.sql.Types.VARCHAR); s.setLong(17,0); s.executeUpdate();
        }
    }

    public void updateRecord(Connection c, String id, RecordRecord r, long revision, String now) throws SQLException {
        try (PreparedStatement s=c.prepareStatement("UPDATE finance_record SET occurred_on=?,record_type=?,amount_minor=?,category_id=?,account_id=?,settlement_mode='PAID_FROM_ACCOUNT',settlement_on=?,note=?,updated_at=?,revision=? WHERE id=?")) {
            s.setString(1,r.occurredOn);s.setString(2,r.recordType);s.setLong(3,r.amountMinor);s.setString(4,r.categoryId);s.setString(5,r.accountId);s.setString(6,r.settlementOn);s.setString(7,r.note);s.setString(8,now);s.setLong(9,revision);s.setString(10,id);s.executeUpdate();
        }
    }

    public void setRecordDeleted(Connection c, String id, String deletedAt, long revision, String now) throws SQLException {
        try (PreparedStatement s=c.prepareStatement("UPDATE finance_record SET deleted_at=?,updated_at=?,revision=? WHERE id=?")) { s.setString(1,deletedAt);s.setString(2,now);s.setLong(3,revision);s.setString(4,id);s.executeUpdate(); }
    }

    public CategoryRecord categoryForRecord(Connection c, String id) throws SQLException { return findCategory(c,id); }
    public AccountRecord accountForRecord(Connection c, String id, LocalDate asOf) throws SQLException { return findAccount(c,id,asOf); }

    public static final class RecordRecord {
        public final String id,occurredOn,recordType,categoryId,accountId,settlementMode,settlementOn,note,createdAt,updatedAt,deletedAt,categoryName,categoryArchived,accountName,accountArchived;
        public final long amountMinor,revision;
        public RecordRecord(String id,String occurredOn,String recordType,long amountMinor,String categoryId,String accountId,String settlementMode,String settlementOn,String note,String createdAt,String updatedAt,String deletedAt,long revision,String categoryName,String categoryArchived,String accountName,String accountArchived){this.id=id;this.occurredOn=occurredOn;this.recordType=recordType;this.amountMinor=amountMinor;this.categoryId=categoryId;this.accountId=accountId;this.settlementMode=settlementMode;this.settlementOn=settlementOn;this.note=note;this.createdAt=createdAt;this.updatedAt=updatedAt;this.deletedAt=deletedAt;this.revision=revision;this.categoryName=categoryName;this.categoryArchived=categoryArchived;this.accountName=accountName;this.accountArchived=accountArchived;}
    }
    private static RecordRecord readRecord(ResultSet r) throws SQLException { return new RecordRecord(r.getString("id"),r.getString("occurred_on"),r.getString("record_type"),r.getLong("amount_minor"),r.getString("category_id"),r.getString("account_id"),r.getString("settlement_mode"),r.getString("settlement_on"),r.getString("note"),r.getString("created_at"),r.getString("updated_at"),r.getString("deleted_at"),r.getLong("revision"),r.getString("category_name"),r.getString("category_archived"),r.getString("account_name"),r.getString("account_archived")); }

    private AccountRecord readAccount(Connection c,ResultSet r,LocalDate asOf) throws SQLException {
        LocalDate opening=LocalDate.parse(r.getString("opening_on")); long openingMinor=r.getLong("opening_balance_minor");
        long balance=opening.isAfter(asOf)?0L:openingMinor;
        if(!opening.isAfter(asOf)){
            try(PreparedStatement s=c.prepareStatement("SELECT record_type,amount_minor FROM finance_record WHERE account_id=? AND deleted_at IS NULL AND settlement_mode='PAID_FROM_ACCOUNT' AND settlement_on<=?")){
                s.setString(1,r.getString("id"));s.setString(2,asOf.toString());try(ResultSet rows=s.executeQuery()){while(rows.next()){long amount=rows.getLong("amount_minor");String type=rows.getString("record_type");long delta="INCOME".equals(type)?amount:-amount; if("LIABILITY".equals(r.getString("balance_side")))delta=-delta; balance=Math.addExact(balance,delta);}}
            }
        }
        return new AccountRecord(r.getString("id"),r.getString("name"),r.getString("kind"),r.getString("balance_side"),opening,openingMinor,r.getInt("include_in_available_cash")==1,r.getInt("is_system")==1,r.getString("archived_at"),r.getString("created_at"),r.getString("updated_at"),r.getLong("revision"),balance);
    }

    public static final class AccountRecord {
        public final String id,name,kind,balanceSide,archivedAt,createdAt,updatedAt; public final LocalDate openingOn; public final long openingBalanceMinor,revision,balanceMinor; public final boolean includeInAvailableCash,system;
        public AccountRecord(String id,String name,String kind,String balanceSide,LocalDate openingOn,long openingBalanceMinor,boolean includeInAvailableCash,boolean system,String archivedAt,String createdAt,String updatedAt,long revision,long balanceMinor){this.id=id;this.name=name;this.kind=kind;this.balanceSide=balanceSide;this.openingOn=openingOn;this.openingBalanceMinor=openingBalanceMinor;this.includeInAvailableCash=includeInAvailableCash;this.system=system;this.archivedAt=archivedAt;this.createdAt=createdAt;this.updatedAt=updatedAt;this.revision=revision;this.balanceMinor=balanceMinor;}
    }

    public void incrementDataRevision(Connection c, String now) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("UPDATE ledger_meta SET updated_at=?,data_revision=data_revision+1 WHERE id=1")) {
            s.setString(1, now); s.executeUpdate();
        }
    }

    public long dataRevision(Connection c) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("SELECT data_revision FROM ledger_meta WHERE id=1"); ResultSet r=s.executeQuery()) {
            return r.next() ? r.getLong(1) : 0L;
        }
    }

    public void insertOperation(Connection c, String key, String method, String path, String hash, int status,
            String response, String profileId, String completedAt, String expiresAt) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("INSERT INTO processed_operation "
                + "(idempotency_key,http_method,canonical_path,request_hash,response_status,response_json,profile_id,completed_at,expires_at) "
                + "VALUES (?,?,?,?,?,?,?,?,?)")) {
            s.setString(1,key);s.setString(2,method);s.setString(3,path);s.setString(4,hash);s.setInt(5,status);
            s.setString(6,response);s.setString(7,profileId);s.setString(8,completedAt);s.setString(9,expiresAt);s.executeUpdate();
        }
    }

    private CategoryRecord readCategory(Connection c, ResultSet r) throws SQLException {
        return new CategoryRecord(r.getString("id"), r.getString("parent_id"), r.getString("name"),
                r.getInt("is_system") == 1, r.getInt("is_legacy_custom") == 1, r.getString("archived_at"),
                r.getInt("sort_order"), r.getString("default_recognition_method"),
                r.getString("recommended_depreciation_method"), r.getString("created_at"),
                r.getString("updated_at"), r.getLong("revision"), recordTypes(c, r.getString("id")));
    }

    public static final class CategoryRecord {
        public final String id, parentId, name, archivedAt, defaultRecognitionMethod,
                recommendedDepreciationMethod, createdAt, updatedAt;
        public final boolean system, legacyCustom;
        public final int sortOrder;
        public final long revision;
        public final List<String> recordTypes;
        public CategoryRecord(String id, String parentId, String name, boolean system, boolean legacyCustom,
                String archivedAt, int sortOrder, String defaultRecognitionMethod,
                String recommendedDepreciationMethod, String createdAt, String updatedAt, long revision,
                List<String> recordTypes) {
            this.id=id;this.parentId=parentId;this.name=name;this.system=system;this.legacyCustom=legacyCustom;
            this.archivedAt=archivedAt;this.sortOrder=sortOrder;this.defaultRecognitionMethod=defaultRecognitionMethod;
            this.recommendedDepreciationMethod=recommendedDepreciationMethod;this.createdAt=createdAt;
            this.updatedAt=updatedAt;this.revision=revision;this.recordTypes=recordTypes;
        }
    }
}
