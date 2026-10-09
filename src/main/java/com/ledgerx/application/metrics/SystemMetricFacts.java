package com.ledgerx.application.metrics;

import java.math.BigDecimal;

public final class SystemMetricFacts {
    private final BigDecimal income;
    private final BigDecimal fixedExpense;
    private final BigDecimal variableExpense;
    private final BigDecimal cashInflow;
    private final BigDecimal cashOutflow;
    private final BigDecimal availableCash;
    private final BigDecimal totalAssets;
    private final BigDecimal totalLiabilities;
    private final long transactionCount;
    private final int periodDays;
    private final boolean hasPeriodRecords;

    private SystemMetricFacts(Builder builder) {
        income = value(builder.income);
        fixedExpense = value(builder.fixedExpense);
        variableExpense = value(builder.variableExpense);
        cashInflow = value(builder.cashInflow);
        cashOutflow = value(builder.cashOutflow);
        availableCash = value(builder.availableCash);
        totalAssets = value(builder.totalAssets);
        totalLiabilities = value(builder.totalLiabilities);
        transactionCount = builder.transactionCount;
        periodDays = builder.periodDays;
        hasPeriodRecords = builder.hasPeriodRecords;
    }

    public static Builder builder() { return new Builder(); }
    public BigDecimal getIncome() { return income; }
    public BigDecimal getFixedExpense() { return fixedExpense; }
    public BigDecimal getVariableExpense() { return variableExpense; }
    public BigDecimal getCashInflow() { return cashInflow; }
    public BigDecimal getCashOutflow() { return cashOutflow; }
    public BigDecimal getAvailableCash() { return availableCash; }
    public BigDecimal getTotalAssets() { return totalAssets; }
    public BigDecimal getTotalLiabilities() { return totalLiabilities; }
    public long getTransactionCount() { return transactionCount; }
    public int getPeriodDays() { return periodDays; }
    public boolean hasPeriodRecords() { return hasPeriodRecords; }

    private static BigDecimal value(BigDecimal value) { return value == null ? BigDecimal.ZERO : value; }

    public static final class Builder {
        private BigDecimal income = BigDecimal.ZERO;
        private BigDecimal fixedExpense = BigDecimal.ZERO;
        private BigDecimal variableExpense = BigDecimal.ZERO;
        private BigDecimal cashInflow = BigDecimal.ZERO;
        private BigDecimal cashOutflow = BigDecimal.ZERO;
        private BigDecimal availableCash = BigDecimal.ZERO;
        private BigDecimal totalAssets = BigDecimal.ZERO;
        private BigDecimal totalLiabilities = BigDecimal.ZERO;
        private long transactionCount;
        private int periodDays = 1;
        private boolean hasPeriodRecords;

        public Builder income(BigDecimal value) { income = value; return this; }
        public Builder fixedExpense(BigDecimal value) { fixedExpense = value; return this; }
        public Builder variableExpense(BigDecimal value) { variableExpense = value; return this; }
        public Builder cashInflow(BigDecimal value) { cashInflow = value; return this; }
        public Builder cashOutflow(BigDecimal value) { cashOutflow = value; return this; }
        public Builder availableCash(BigDecimal value) { availableCash = value; return this; }
        public Builder totalAssets(BigDecimal value) { totalAssets = value; return this; }
        public Builder totalLiabilities(BigDecimal value) { totalLiabilities = value; return this; }
        public Builder transactionCount(long value) { transactionCount = value; return this; }
        public Builder periodDays(int value) { periodDays = value; return this; }
        public Builder hasPeriodRecords(boolean value) { hasPeriodRecords = value; return this; }
        public SystemMetricFacts build() {
            if (transactionCount < 0 || periodDays < 1) throw new IllegalArgumentException("facts counts are invalid");
            return new SystemMetricFacts(this);
        }
    }
}
