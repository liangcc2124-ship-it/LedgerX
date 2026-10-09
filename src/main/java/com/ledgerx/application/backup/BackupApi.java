package com.ledgerx.application.backup;

public interface BackupApi {
    BackupApiResult createBackup(String idempotencyKey) throws BackupException;

    BackupApiResult listBackups(int limit, String cursor) throws BackupException;

    BackupApiResult verifyBackup(String id) throws BackupException;

    BackupDownload openBackupDownload(String id) throws BackupException;
}
