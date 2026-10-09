package com.ledgerx.application.bootstrap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataDirectoryLockTest {
    @TempDir
    Path dataDirectory;

    @Test
    void onlyOneWriterInThisProcessCanOwnTheDirectoryAndLockCanBeReacquired() throws Exception {
        try (DataDirectoryLock ignored = DataDirectoryLock.acquire(dataDirectory)) {
            IOException error = assertThrows(IOException.class, () -> DataDirectoryLock.acquire(dataDirectory));
            assertTrue(error.getMessage().contains("already using this data directory"));
        }

        assertDoesNotThrow(() -> {
            try (DataDirectoryLock ignored = DataDirectoryLock.acquire(dataDirectory)) {
                // Releasing the operating-system lock makes a later application start possible.
            }
        });
    }
}
