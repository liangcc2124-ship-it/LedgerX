package com.ledgerx.application.bootstrap;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Holds an operating-system lock so only one LedgerX process can write a data directory. */
public final class DataDirectoryLock implements AutoCloseable {
    private static final String LOCK_FILE_NAME = ".ledgerx-writer.lock";

    private final FileChannel channel;
    private final FileLock lock;

    private DataDirectoryLock(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    public static DataDirectoryLock acquire(Path dataDirectory) throws IOException {
        if (dataDirectory == null) {
            throw new IllegalArgumentException("dataDirectory is required");
        }
        Path root = dataDirectory.toAbsolutePath().normalize();
        Files.createDirectories(root);
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(root)) {
            throw new IOException("LedgerX data directory is not a safe directory");
        }

        Path lockFile = root.resolve(LOCK_FILE_NAME);
        if (Files.isSymbolicLink(lockFile)) {
            throw new IOException("LedgerX writer lock path is not safe");
        }

        FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        try {
            FileLock lock = channel.tryLock();
            if (lock == null) {
                throw alreadyRunning();
            }
            return new DataDirectoryLock(channel, lock);
        } catch (OverlappingFileLockException ex) {
            closeQuietly(channel);
            throw alreadyRunning();
        } catch (IOException | RuntimeException ex) {
            closeQuietly(channel);
            throw ex;
        }
    }

    private static IOException alreadyRunning() {
        return new IOException("Another LedgerX service is already using this data directory.");
    }

    @Override
    public void close() throws IOException {
        try {
            lock.release();
        } finally {
            channel.close();
        }
    }

    private static void closeQuietly(FileChannel channel) {
        try {
            channel.close();
        } catch (IOException ignored) {
            // Keep the lock acquisition failure as the useful error.
        }
    }
}
