package com.ledgerx.application.metrics;

import java.util.Objects;

public final class FormulaToken {
    private final String type;
    private final String value;
    private final String path;

    public FormulaToken(String type, String value, String path) {
        this.type = Objects.requireNonNull(type, "type");
        this.value = Objects.requireNonNull(value, "value");
        this.path = Objects.requireNonNull(path, "path");
    }

    public String getType() { return type; }
    public String getValue() { return value; }
    public String getPath() { return path; }
}
