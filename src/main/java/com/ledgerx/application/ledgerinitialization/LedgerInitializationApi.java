package com.ledgerx.application.ledgerinitialization;

public interface LedgerInitializationApi {
    LedgerInitializationApiResult readInitialization() throws LedgerInitializationException;

    LedgerInitializationApiResult initializeLedger(LedgerInitializationDraft draft,
            LedgerInitializationMutation mutation) throws LedgerInitializationException;
}
