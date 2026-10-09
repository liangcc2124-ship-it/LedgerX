package com.ledgerx.application.metrics;

public interface MetricsApi {
    MetricApiResult listMetrics(MetricListQuery query) throws MetricException;
    default MetricApiResult listMetrics(boolean includeArchived) throws MetricException {
        return listMetrics(new MetricListQuery(includeArchived ? "ARCHIVED" : "ACTIVE", null, 200, null));
    }
    MetricApiResult getMetric(String id, boolean includeArchived) throws MetricException;
    MetricApiResult createMetric(MetricDraft draft, MetricMutation mutation) throws MetricException;
    MetricApiResult updateMetric(String id, long expectedRevision, MetricDraft draft, MetricMutation mutation)
            throws MetricException;
    MetricApiResult updateSystemVisibility(String id, long expectedRevision, boolean hidden, boolean dashboardEnabled,
            MetricMutation mutation) throws MetricException;
    MetricApiResult archiveMetric(String id, long expectedRevision, MetricMutation mutation) throws MetricException;
    MetricApiResult validateFormula(FormulaDraftRequest draft) throws MetricException;
    MetricApiResult previewFormula(FormulaDraftRequest draft) throws MetricException;
    MetricApiResult listFormulaVersions(String formulaId, int limit, String cursor) throws MetricException;
}
