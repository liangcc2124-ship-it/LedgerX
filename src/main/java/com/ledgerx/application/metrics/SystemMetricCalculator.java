package com.ledgerx.application.metrics;

import java.math.BigDecimal;
import java.math.MathContext;

public final class SystemMetricCalculator {
    private static final MathContext CONTEXT = MathContext.DECIMAL128;

    public FormulaEvaluationResult calculate(String metricId, SystemMetricFacts facts) {
        if (metricId == null || facts == null) return FormulaEvaluationResult.unavailable();
        BigDecimal expense = facts.getFixedExpense().add(facts.getVariableExpense(), CONTEXT);
        switch (metricId) {
            case "income": return flow(facts, facts.getIncome());
            case "fixed-expense": return flow(facts, facts.getFixedExpense());
            case "variable-expense": return flow(facts, facts.getVariableExpense());
            case "total-expense": return flow(facts, expense);
            case "net-result": return flow(facts, facts.getIncome().subtract(expense, CONTEXT));
            case "savings-rate": return percentage(facts, facts.getIncome().subtract(expense, CONTEXT), facts.getIncome());
            case "cash-inflow": return flow(facts, facts.getCashInflow());
            case "cash-outflow": return flow(facts, facts.getCashOutflow());
            case "net-cash-flow": return flow(facts, facts.getCashInflow().subtract(facts.getCashOutflow(), CONTEXT));
            case "available-cash": return FormulaEvaluationResult.ready(facts.getAvailableCash());
            case "total-assets": return FormulaEvaluationResult.ready(facts.getTotalAssets());
            case "total-liabilities": return FormulaEvaluationResult.ready(facts.getTotalLiabilities());
            case "net-assets": return FormulaEvaluationResult.ready(facts.getTotalAssets().subtract(facts.getTotalLiabilities(), CONTEXT));
            case "average-daily-expense": return flow(facts, expense.divide(BigDecimal.valueOf(facts.getPeriodDays()), CONTEXT));
            case "fixed-expense-ratio": return percentage(facts, facts.getFixedExpense(), expense);
            case "variable-expense-ratio": return percentage(facts, facts.getVariableExpense(), expense);
            case "transaction-count": return facts.hasPeriodRecords()
                    ? FormulaEvaluationResult.ready(BigDecimal.valueOf(facts.getTransactionCount()))
                    : FormulaEvaluationResult.empty();
            default: return FormulaEvaluationResult.unavailable();
        }
    }

    private static FormulaEvaluationResult flow(SystemMetricFacts facts, BigDecimal value) {
        return facts.hasPeriodRecords() ? FormulaEvaluationResult.ready(value) : FormulaEvaluationResult.empty();
    }

    private static FormulaEvaluationResult percentage(SystemMetricFacts facts, BigDecimal numerator, BigDecimal denominator) {
        if (!facts.hasPeriodRecords()) return FormulaEvaluationResult.empty();
        if (denominator.signum() == 0) return FormulaEvaluationResult.notComputable();
        return FormulaEvaluationResult.ready(numerator.multiply(BigDecimal.valueOf(100), CONTEXT)
                .divide(denominator, CONTEXT));
    }
}
