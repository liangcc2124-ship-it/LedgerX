package com.ledgerx.application.metrics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerx.application.profile.ProfileApplicationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static com.ledgerx.testsupport.LedgerTestSupport.openReadyLedger;

class MetricsApplicationTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-13T02:00:00Z"), ZoneOffset.UTC);
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void readsDashboardAndPersistsCustomMetricWithIdempotency(@TempDir Path temp) throws Exception {
        ProfileApplicationService service = openReadyLedger(temp, "test", CLOCK);
        assertTrue(service.current().getCapabilities().contains("formulas.validate"));
        assertTrue(service.current().getCapabilities().contains("formulas.write"));
        JsonNode list = JSON.readTree(service.listMetrics(false).getResponseJson());
        assertEquals(17, list.at("/data/items").size());
        JsonNode dashboard = JSON.readTree(service.readDashboard(MetricGranularity.MONTH, LocalDate.of(2026, 9, 13)).getResponseJson());
        assertEquals(8, dashboard.at("/data/cards").size());

        String id = "custom-3d407ca8-ec1c-4a48-8bd6-edc34c7894b3";
        MetricDraft draft = new MetricDraft(id, "测试指标", "说明", "NUMBER", 2, "PERIOD",
                FormulaNode.constant("12.5"), Collections.emptyList(), false, true);
        MetricMutation mutation = new MetricMutation(UUID.randomUUID().toString(), "POST", "/api/v1/metrics", "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
        MetricApiResult created = service.createMetric(draft, mutation);
        assertEquals(201, created.getStatus());
        assertEquals(created.getResponseJson(), service.createMetric(draft, mutation).getResponseJson());
        assertEquals(18, JSON.readTree(service.listMetrics(false).getResponseJson()).at("/data/items").size());
        JsonNode customDashboard = JSON.readTree(service.readDashboard(MetricGranularity.MONTH,
                LocalDate.of(2026, 9, 13)).getResponseJson());
        JsonNode customCard = null;
        for (JsonNode card : customDashboard.at("/data/cards")) {
            if (id.equals(card.at("/metric/id").asText())) { customCard = card; break; }
        }
        assertNotNull(customCard);
        assertEquals("12.5", customCard.at("/value/value").asText());
        assertEquals(9, customDashboard.at("/data/layout/items").size());
        assertEquals(false, customDashboard.at("/data/layout/items/8/persisted").asBoolean());
        String categoryId = JSON.readTree(service.listCategories(false, 200, null).getResponseJson())
                .at("/data/items/0/id").asText();
        String categoryMetricId = "custom-3d407ca8-ec1c-4a48-8bd6-edc34c7894b4";
        MetricDraft categoryDraft = new MetricDraft(categoryMetricId, "分类支出", "说明", "CURRENCY", 2, "PERIOD",
                FormulaNode.reference(new FormulaReference(FormulaReference.Kind.CATEGORY_EXPENSE, categoryId)),
                Collections.emptyList(), false, true);
        service.createMetric(categoryDraft, new MetricMutation(UUID.randomUUID().toString(), "POST", "/api/v1/metrics",
                "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789"));
        JsonNode categoryDashboard = JSON.readTree(service.readDashboard(MetricGranularity.MONTH,
                LocalDate.of(2026, 9, 13)).getResponseJson());
        JsonNode categoryCard = null;
        for (JsonNode card : categoryDashboard.at("/data/cards")) {
            if (categoryMetricId.equals(card.at("/metric/id").asText())) { categoryCard = card; break; }
        }
        assertNotNull(categoryCard);
        assertEquals("READY", categoryCard.at("/value/dataStatus").asText());

        String dependentId = "custom-3d407ca8-ec1c-4a48-8bd6-edc34c7894b5";
        service.createMetric(new MetricDraft(dependentId, "依赖指标", "说明", "NUMBER", 2, "PERIOD",
                FormulaNode.reference(new FormulaReference(FormulaReference.Kind.METRIC, id)), Collections.emptyList(),
                false, false), new MetricMutation(UUID.randomUUID().toString(), "POST", "/api/v1/metrics",
                "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210"));
        MetricException cycle = assertThrows(MetricException.class, () -> service.updateMetric(id, 0L,
                new MetricDraft(id, "测试指标", "说明", "NUMBER", 2, "PERIOD",
                        FormulaNode.reference(new FormulaReference(FormulaReference.Kind.METRIC, dependentId)),
                        Collections.emptyList(), false, true),
                new MetricMutation(UUID.randomUUID().toString(), "PUT", "/api/v1/metrics/" + id,
                        "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff")));
        assertEquals("FORMULA_CYCLE", cycle.getCode());
        MetricApiResult archived = service.archiveMetric(categoryMetricId, 0L,
                new MetricMutation(UUID.randomUUID().toString(), "DELETE", "/api/v1/metrics/" + categoryMetricId,
                        "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789"));
        assertEquals(200, archived.getStatus());
        assertThrows(MetricException.class, () -> service.getMetric(categoryMetricId, false));
    }
}
