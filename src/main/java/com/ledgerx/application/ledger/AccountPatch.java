package com.ledgerx.application.ledger;

import java.time.LocalDate;

public final class AccountPatch {
    private final String id,name,kind; private final LocalDate openingOn; private final long openingBalanceMinor; private final boolean includeInAvailableCash;
    public AccountPatch(String id,String name,String kind,LocalDate openingOn,long openingBalanceMinor,boolean includeInAvailableCash){this.id=id;this.name=name;this.kind=kind;this.openingOn=openingOn;this.openingBalanceMinor=openingBalanceMinor;this.includeInAvailableCash=includeInAvailableCash;}
    public String getId(){return id;} public String getName(){return name;} public String getKind(){return kind;} public LocalDate getOpeningOn(){return openingOn;} public long getOpeningBalanceMinor(){return openingBalanceMinor;} public boolean isIncludeInAvailableCash(){return includeInAvailableCash;}
}
