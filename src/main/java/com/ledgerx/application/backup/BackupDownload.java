package com.ledgerx.application.backup;

import java.nio.file.Path;

public final class BackupDownload {
    private final String id;
    private final Path file;
    private final long size;

    public BackupDownload(String id, Path file, long size) {
        this.id = id;
        this.file = file;
        this.size = size;
    }

    public String getId() { return id; }
    public Path getFile() { return file; }
    public long getSize() { return size; }
}
