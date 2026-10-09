package com.ledgerx.application.metrics;

import java.util.Objects;

public final class FormulaError {
    private final String code;
    private final String path;
    private final String message;

    public FormulaError(String code, String path, String message) {
        this.code = Objects.requireNonNull(code, "code");
        this.path = Objects.requireNonNull(path, "path");
        this.message = Objects.requireNonNull(message, "message");
    }

    public String getCode() { return code; }
    public String getPath() { return path; }
    public String getMessage() { return message; }
}
