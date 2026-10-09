package com.ledgerx.application.metrics;

import java.util.Objects;

public final class FormulaReference {
    public enum Kind {
        METRIC,
        CATEGORY_INCOME,
        CATEGORY_EXPENSE,
        ACCOUNT_BALANCE,
        TIME
    }

    private final Kind kind;
    private final String key;

    public FormulaReference(Kind kind, String key) {
        this.kind = Objects.requireNonNull(kind, "kind");
        if (key == null || key.trim().isEmpty() || key.length() > 120) {
            throw new IllegalArgumentException("reference key is required and must be at most 120 characters");
        }
        this.key = key;
    }

    public Kind getKind() { return kind; }
    public String getKey() { return key; }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof FormulaReference)) return false;
        FormulaReference that = (FormulaReference) other;
        return kind == that.kind && key.equals(that.key);
    }

    @Override
    public int hashCode() { return Objects.hash(kind, key); }

    @Override
    public String toString() { return kind.name() + ":" + key; }
}
