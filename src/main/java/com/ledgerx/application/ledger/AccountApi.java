package com.ledgerx.application.ledger;

import java.time.LocalDate;

public interface AccountApi {
    AccountApiResult listAccounts(boolean includeArchived, LocalDate asOf, int limit, String cursor) throws AccountException;
    AccountApiResult createAccount(AccountPatch patch, AccountMutation mutation) throws AccountException;
    AccountApiResult replaceAccount(String id, long expectedRevision, AccountPatch patch,
            AccountMutation mutation) throws AccountException;
    AccountApiResult archiveAccount(String id, long expectedRevision, AccountMutation mutation) throws AccountException;
}
