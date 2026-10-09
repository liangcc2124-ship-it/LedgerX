package com.ledgerx.application.metrics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerx.http.FormulaHttpMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetricsDomainTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-15T01:00:00Z"),
            ZoneId.of("Asia/Shanghai"));
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void periodResolverNormalizesFourGranularitiesAndComparisonWindow() {
        PeriodResolver resolver = new PeriodResolver(CLOCK);
        ResolvedPeriod day = resolver.resolve(new DashboardPeriod(MetricGranularity.DAY, LocalDate.of(2026, 9, 15)));
        assertEquals(LocalDate.of(2026, 9, 15), day.getStart());
        assertEquals(LocalDate.of(2026, 9, 16), day.getEndExclusive());
        assertEquals(LocalDate.of(2026, 9, 14), day.getComparisonStart());
        assertEquals(LocalDate.of(2026, 9, 15), day.getComparisonEndExclusive());
        assertEquals(PeriodStatus.CURRENT, day.getStatus());

        ResolvedPeriod week = resolver.resolve(new DashboardPeriod(MetricGranularity.WEEK, LocalDate.of(2026, 9, 20)));
        assertEquals(LocalDate.of(2026, 9, 14), week.getStart());
        assertEquals(LocalDate.of(2026, 9, 21), week.getEndExclusive());
        assertEquals(LocalDate.of(2026, 9, 7), week.getComparisonStart());
        assertEquals(LocalDate.of(2026, 9, 9), week.getComparisonEndExclusive());

        ResolvedPeriod month = resolver.resolve(new DashboardPeriod(MetricGranularity.MONTH, LocalDate.of(2026, 9, 30)));
        assertEquals(LocalDate.of(2026, 9, 1), month.getStart());
        assertEquals(LocalDate.of(2026, 9, 15), month.getAsOf());
        assertEquals(LocalDate.of(2026, 8, 16), month.getComparisonEndExclusive());

        ResolvedPeriod year = resolver.resolve(new DashboardPeriod(MetricGranularity.YEAR, LocalDate.of(2026, 3, 2)));
        assertEquals(LocalDate.of(2026, 1, 1), year.getStart());
        assertEquals(LocalDate.of(2025, 1, 1), year.getPreviousStart());

        ResolvedPeriod future = resolver.resolve(new DashboardPeriod(MetricGranularity.MONTH, LocalDate.of(2026, 10, 1)));
        assertEquals(PeriodStatus.FUTURE, future.getStatus());
        assertEquals(null, future.getAsOf());
    }

    @Test
    void chineseFunctionAliasesNormalizeToStableNames() {
        assertEquals("ADD", FormulaTokenNormalizer.normalizeFunction("加"));
        assertEquals("SAFE_DIVIDE", FormulaTokenNormalizer.normalizeFunction("安全除法"));
        FormulaToken normalized = FormulaTokenNormalizer.normalize(new FormulaToken("FUNCTION", "四舍五入", "formula.tokens[0]"));
        assertEquals("ROUND", normalized.getValue());
    }

    @Test
    void validatorEnforcesReferencesArityLimitsAndCycles() {
        Set<String> known = new HashSet<>(Arrays.asList("income", "expense", "margin"));
        FormulaNode valid = FormulaNode.operation(FormulaNode.Kind.ADD, Arrays.asList(
                FormulaNode.reference(new FormulaReference(FormulaReference.Kind.METRIC, "income")),
                FormulaNode.constant("2.50")));
        FormulaValidationResult result = new FormulaValidator().validate(valid, "margin", known);
        assertTrue(result.isValid());
        assertEquals(1, result.getDependencies().size());

        FormulaNode self = FormulaNode.reference(new FormulaReference(FormulaReference.Kind.METRIC, "margin"));
        FormulaValidationResult selfResult = new FormulaValidator().validate(self, "margin", known);
        assertFalse(selfResult.isValid());
        assertEquals("FORMULA_CYCLE", selfResult.getErrors().get(0).getCode());

        Map<String, Set<String>> graph = new HashMap<>();
        graph.put("income", Collections.singleton("expense"));
        FormulaNode indirect = FormulaNode.reference(new FormulaReference(FormulaReference.Kind.METRIC, "income"));
        FormulaValidationResult cycle = new FormulaValidator().validate(indirect, "margin", known, graph);
        assertTrue(cycle.isValid());
        graph.put("income", Collections.singleton("margin"));
        cycle = new FormulaValidator().validate(indirect, "margin", known, graph);
        assertFalse(cycle.isValid());

        FormulaNode badArity = FormulaNode.operation(FormulaNode.Kind.ADD,
                Collections.singletonList(FormulaNode.constant("1")));
        assertFalse(new FormulaValidator().validate(badArity, "margin", known).isValid());
    }

    @Test
    void formulaMappersRoundTripExternalAndLegacyStorageShapes() throws Exception {
        JsonNode ast = JSON.readTree("{\"schemaVersion\":1,\"root\":{\"kind\":\"SUBTRACT\",\"children\":["
                + "{\"kind\":\"CONSTANT\",\"value\":\"12.5\"},{\"kind\":\"CONSTANT\",\"value\":\"2\"}]}}");
        FormulaNode root = FormulaHttpMapper.parseAst(ast);
        assertEquals(FormulaNode.Kind.SUB, root.getKind());
        JsonNode external = JSON.valueToTree(FormulaHttpMapper.toAst(root));
        assertEquals("SUBTRACT", external.at("/root/kind").asText());
        assertEquals("CONSTANT", external.at("/root/children/0/kind").asText());
        assertFalse(external.at("/root/children/0/root").isObject());
        JsonNode storage = JSON.valueToTree(FormulaStorageMapper.toAst(root));
        assertEquals(FormulaNode.Kind.SUB, FormulaStorageMapper.fromAst(storage).getKind());
        JsonNode legacy = JSON.readTree("{\"schemaVersion\":1,\"root\":{\"kind\":\"SUB\",\"children\":["
                + "{\"schemaVersion\":1,\"root\":{\"kind\":\"CONSTANT\",\"value\":\"12.5\"}},"
                + "{\"schemaVersion\":1,\"root\":{\"kind\":\"CONSTANT\",\"value\":\"2\"}}]}}");
        assertEquals(FormulaNode.Kind.SUB, FormulaStorageMapper.fromAst(legacy).getKind());
        assertEquals("SUBTRACT", CanonicalFormulaTokens.from(root).get(2).getValue());
    }

    @Test
    void evaluatorUsesDecimal128AndReturnsStableZeroDivisionStatuses() {
        Map<String, BigDecimal> metrics = Collections.singletonMap("income", new BigDecimal("10.00"));
        FormulaEvaluationContext context = new FormulaEvaluationContext(metrics, null, null, null,
                Collections.singletonMap("period-days", BigDecimal.valueOf(30)));
        FormulaEvaluator evaluator = new FormulaEvaluator();
        FormulaNode add = FormulaNode.operation(FormulaNode.Kind.ADD, Arrays.asList(
                FormulaNode.reference(new FormulaReference(FormulaReference.Kind.METRIC, "income")),
                FormulaNode.constant("0.10")));
        assertEquals(new BigDecimal("10.10"), evaluator.evaluate(add, context).getValue());

        FormulaNode divide = FormulaNode.operation(FormulaNode.Kind.DIV,
                Arrays.asList(FormulaNode.constant("1"), FormulaNode.constant("0")));
        assertEquals(MetricDataStatus.NOT_COMPUTABLE, evaluator.evaluate(divide, context).getStatus());
        FormulaNode safe = FormulaNode.operation(FormulaNode.Kind.SAFE_DIVIDE,
                Arrays.asList(FormulaNode.constant("1"), FormulaNode.constant("0")));
        assertEquals(MetricDataStatus.NOT_COMPUTABLE, evaluator.evaluate(safe, context).getStatus());
        FormulaNode rounded = FormulaNode.operation(FormulaNode.Kind.ROUND,
                Arrays.asList(FormulaNode.constant("1.235"), FormulaNode.constant("2")));
        assertEquals(new BigDecimal("1.24"), evaluator.evaluate(rounded, context).getValue());
        FormulaNode clamp = FormulaNode.operation(FormulaNode.Kind.CLAMP, Arrays.asList(
                FormulaNode.constant("12"), FormulaNode.constant("0"), FormulaNode.constant("10")));
        assertEquals(new BigDecimal("10"), evaluator.evaluate(clamp, context).getValue());
    }

    @Test
    void allSystemMetricsUseFactsAndZeroDenominatorsAreNotFakeZero() {
        SystemMetricFacts facts = SystemMetricFacts.builder()
                .income(new BigDecimal("100"))
                .fixedExpense(new BigDecimal("20"))
                .variableExpense(new BigDecimal("30"))
                .cashInflow(new BigDecimal("100"))
                .cashOutflow(new BigDecimal("50"))
                .availableCash(new BigDecimal("900"))
                .totalAssets(new BigDecimal("1000"))
                .totalLiabilities(new BigDecimal("100"))
                .transactionCount(5)
                .periodDays(10)
                .hasPeriodRecords(true)
                .build();
        SystemMetricCalculator calculator = new SystemMetricCalculator();
        String[] ids = {"income", "fixed-expense", "variable-expense", "total-expense", "net-result",
                "savings-rate", "cash-inflow", "cash-outflow", "net-cash-flow", "available-cash",
                "total-assets", "total-liabilities", "net-assets", "average-daily-expense",
                "fixed-expense-ratio", "variable-expense-ratio", "transaction-count"};
        for (String id : ids) assertEquals(MetricDataStatus.READY, calculator.calculate(id, facts).getStatus(), id);
        assertEquals(new BigDecimal("50"), calculator.calculate("net-result", facts).getValue());
        assertEquals(new BigDecimal("50"), calculator.calculate("savings-rate", facts).getValue());
        assertEquals(new BigDecimal("60"),
                calculator.calculate("variable-expense-ratio", facts).getValue());

        SystemMetricFacts empty = SystemMetricFacts.builder().hasPeriodRecords(false).build();
        assertEquals(MetricDataStatus.EMPTY, calculator.calculate("income", empty).getStatus());
        assertEquals(MetricDataStatus.EMPTY, calculator.calculate("savings-rate", empty).getStatus());
    }
}
