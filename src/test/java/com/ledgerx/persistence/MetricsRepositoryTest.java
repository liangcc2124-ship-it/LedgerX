package com.ledgerx.persistence;

import com.ledgerx.application.metrics.DashboardPeriod;
import com.ledgerx.application.metrics.MetricGranularity;
import com.ledgerx.application.metrics.PeriodResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MetricsRepositoryTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-15T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void readsDefinitionsLayoutAndFactsFromOneRealSqliteSnapshot(@TempDir Path temp) throws Exception {
        BootstrapSnapshot snapshot = new ProfileBootstrap(temp, CLOCK).open();
        Path ledger = temp.resolve("Profiles").resolve(snapshot.getProfileId()).resolve("ledger.db");
        LedgerMetricsRepository repository = new LedgerMetricsRepository(ledger);
        try (Connection connection = repository.openConnection()) {
            assertEquals(17, repository.listMetricDefinitions(connection, false).size());
            LedgerMetricsRepository.LayoutRecord layout = repository.readLayout(connection);
            assertNotNull(layout);
            assertEquals(8, layout.items.size());
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate(
                        "INSERT INTO finance_record "
                                + "(id,occurred_on,record_type,amount_minor,currency_code,category_id,account_id,settlement_mode,settlement_on,note,created_at,updated_at) "
                                + "SELECT 'e3a4b7d1-9f43-4b6e-9cf2-123456789001','2026-09-15','INCOME',1234,'CNY',c.id,a.id,'PAID_FROM_ACCOUNT','2026-09-15','test','2026-09-15T00:00:00Z','2026-09-15T00:00:00Z' "
                                + "FROM category c, financial_account a WHERE c.is_system=1 AND a.is_system=1 LIMIT 1");
            }
            PeriodResolver resolver = new PeriodResolver(CLOCK);
            LedgerMetricsRepository.MetricFactsRecord facts = repository.aggregateFacts(connection,
                    resolver.resolve(new DashboardPeriod(MetricGranularity.DAY, LocalDate.of(2026, 9, 15))));
            assertEquals(new BigDecimal("12.34"), facts.facts.getIncome());
            assertEquals(1, facts.facts.getTransactionCount());
            try (java.sql.PreparedStatement lookup = connection.prepareStatement(
                    "SELECT category_id,account_id FROM finance_record WHERE id=?")) {
                lookup.setString(1, "e3a4b7d1-9f43-4b6e-9cf2-123456789001");
                try (java.sql.ResultSet row = lookup.executeQuery()) {
                    row.next();
                    assertEquals(new BigDecimal("12.34"), facts.categoryIncome.get(row.getString(1)));
                    assertNotNull(facts.accountBalances.get(row.getString(2)));
                }
            }
            assertEquals(0L, facts.dataRevision);
        }
    }

    @Test
    void formulaVersionsDependenciesVisibilityAndLayoutAreAtomic(@TempDir Path temp) throws Exception {
        BootstrapSnapshot snapshot = new ProfileBootstrap(temp, CLOCK).open();
        Path ledger = temp.resolve("Profiles").resolve(snapshot.getProfileId()).resolve("ledger.db");
        LedgerMetricsRepository repository = new LedgerMetricsRepository(ledger);
        try (Connection connection = repository.openConnection()) {
            connection.setAutoCommit(false);
            repository.insertFormulaDefinition(connection, "f-custom", "CURRENCY", "2026-09-15T00:00:00Z");
            repository.insertMetricDefinition(connection, "custom", "自定义", "测试", "CURRENCY", 2,
                    "PERIOD", false, "2026-09-15T00:00:00Z");
            repository.insertFormulaVersion(connection, "v-custom-1", "f-custom", 1, "{\"kind\":\"constant\"}",
                    "[{\"type\":\"CONSTANT\",\"value\":\"1\"}]", "2026-09-15T00:00:00Z");
            repository.insertDependencies(connection, "v-custom-1", Collections.singletonList(
                    new LedgerMetricsRepository.DependencyRecord("TIME", "period-days", null, null, null)));
            repository.setCurrentFormulaVersion(connection, "custom", "v-custom-1", 1, "2026-09-15T00:00:00Z");
            repository.updateVisibility(connection, "custom", false, true, 1, 2, "2026-09-15T00:00:00Z");
            repository.replaceLayout(connection, "00000000-0000-0000-0000-000000000005", 0,
                    Arrays.asList(new LedgerMetricsRepository.LayoutItemRecord("metric:income", 0, 0, 3, 2,
                            3, 2, 12, 6)), "2026-09-15T00:00:00Z");
            connection.commit();

            LedgerMetricsRepository.MetricDefinitionRecord metric = repository.findMetric(connection, "custom");
            assertEquals("v-custom-1", metric.currentFormulaVersionId);
            assertEquals(2, metric.revision);
            assertEquals(1, repository.readLayout(connection).revision);
        }

        try (Connection connection = repository.openConnection()) {
            connection.setAutoCommit(false);
            repository.insertFormulaVersion(connection, "v-custom-2", "f-custom", 2, "{}", "{}",
                    "2026-09-15T00:00:00Z");
            connection.rollback();
            assertEquals(1, scalar(connection, "SELECT COUNT(*) FROM formula_version WHERE formula_id='f-custom'"));
        }
    }

    @Test
    void layoutRevisionConflictDoesNotDeleteItems(@TempDir Path temp) throws Exception {
        BootstrapSnapshot snapshot = new ProfileBootstrap(temp, CLOCK).open();
        Path ledger = temp.resolve("Profiles").resolve(snapshot.getProfileId()).resolve("ledger.db");
        LedgerMetricsRepository repository = new LedgerMetricsRepository(ledger);
        try (Connection connection = repository.openConnection()) {
            connection.setAutoCommit(false);
            assertThrows(java.sql.SQLException.class, () -> repository.replaceLayout(connection,
                    "00000000-0000-0000-0000-000000000005", 99, Collections.emptyList(), "now"));
            connection.rollback();
            assertEquals(8, repository.readLayout(connection).items.size());
        }
    }

    private static long scalar(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement(); java.sql.ResultSet rows = statement.executeQuery(sql)) {
            rows.next(); return rows.getLong(1);
        }
    }
}
