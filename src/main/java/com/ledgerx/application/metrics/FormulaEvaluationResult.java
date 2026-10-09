package com.ledgerx.application.metrics;

import java.math.BigDecimal;

public final class FormulaEvaluationResult {
    private final BigDecimal value;
    private final MetricDataStatus status;

    private FormulaEvaluationResult(BigDecimal value, MetricDataStatus status) {
        this.value = value;
        this.status = status;
    }

    public static FormulaEvaluationResult ready(BigDecimal value) {
        if (value == null) throw new IllegalArgumentException("value is required");
        return new FormulaEvaluationResult(value, MetricDataStatus.READY);
    }

    public static FormulaEvaluationResult unavailable() {
        return new FormulaEvaluationResult(null, MetricDataStatus.DEPENDENCY_UNAVAILABLE);
    }

    public static FormulaEvaluationResult notComputable() {
        return new FormulaEvaluationResult(null, MetricDataStatus.NOT_COMPUTABLE);
    }

    public static FormulaEvaluationResult empty() {
        return new FormulaEvaluationResult(null, MetricDataStatus.EMPTY);
    }

    public static FormulaEvaluationResult future() {
        return new FormulaEvaluationResult(null, MetricDataStatus.FUTURE);
    }

    public BigDecimal getValue() { return value; }
    public MetricDataStatus getStatus() { return status; }
    public boolean isReady() { return status == MetricDataStatus.READY; }
}
