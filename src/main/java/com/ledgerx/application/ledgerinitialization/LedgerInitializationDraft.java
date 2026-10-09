package com.ledgerx.application.ledgerinitialization;

import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class LedgerInitializationDraft {
    private final LocalDate ledgerStartOn;
    private final LocalDate defaultAccountOpeningOn;
    private final String openingBalance;
    private final String accountName;
    private final boolean confirmExistingData;
    private final Map<String, LocalDate> accountOpeningDates;

    public LedgerInitializationDraft(LocalDate ledgerStartOn, LocalDate defaultAccountOpeningOn,
            String openingBalance, String accountName, boolean confirmExistingData,
            Map<String, LocalDate> accountOpeningDates) {
        this.ledgerStartOn = ledgerStartOn;
        this.defaultAccountOpeningOn = defaultAccountOpeningOn;
        this.openingBalance = openingBalance;
        this.accountName = accountName;
        this.confirmExistingData = confirmExistingData;
        this.accountOpeningDates = Collections.unmodifiableMap(new LinkedHashMap<>(accountOpeningDates));
    }

    public LocalDate getLedgerStartOn() { return ledgerStartOn; }
    public LocalDate getDefaultAccountOpeningOn() { return defaultAccountOpeningOn; }
    public String getOpeningBalance() { return openingBalance; }
    public String getAccountName() { return accountName; }
    public boolean isConfirmExistingData() { return confirmExistingData; }
    public Map<String, LocalDate> getAccountOpeningDates() { return accountOpeningDates; }
}
