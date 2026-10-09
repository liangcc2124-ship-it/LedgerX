package com.ledgerx.application.metrics;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public final class FormulaEvaluationContext {
    private final Map<String, BigDecimal> metrics;
    private final Map<String, BigDecimal> incomeCategories;
    private final Map<String, BigDecimal> expenseCategories;
    private final Map<String, BigDecimal> accounts;
    private final Map<String, BigDecimal> time;

    public FormulaEvaluationContext(Map<String, BigDecimal> metrics,
            Map<String, BigDecimal> incomeCategories, Map<String, BigDecimal> expenseCategories,
            Map<String, BigDecimal> accounts, Map<String, BigDecimal> time) {
        this.metrics = copy(metrics);
        this.incomeCategories = copy(incomeCategories);
        this.expenseCategories = copy(expenseCategories);
        this.accounts = copy(accounts);
        this.time = copy(time);
    }

    public BigDecimal resolve(FormulaReference reference) {
        switch (reference.getKind()) {
            case METRIC: return metrics.get(reference.getKey());
            case CATEGORY_INCOME: return incomeCategories.get(reference.getKey());
            case CATEGORY_EXPENSE: return expenseCategories.get(reference.getKey());
            case ACCOUNT_BALANCE: return accounts.get(reference.getKey());
            case TIME: return time.get(reference.getKey());
            default: return null;
        }
    }

    private static Map<String, BigDecimal> copy(Map<String, BigDecimal> values) {
        return values == null ? Collections.emptyMap() : Collections.unmodifiableMap(new HashMap<>(values));
    }
}
