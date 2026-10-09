package com.ledgerx.application.metrics;

/** Closed application input for the pageable metric collection projection. */
public final class MetricListQuery {
    private final String status;
    private final Boolean dashboardEnabled;
    private final int limit;
    private final String cursor;

    public MetricListQuery(String status, Boolean dashboardEnabled, int limit, String cursor) {
        this.status = status == null ? "ACTIVE" : status;
        this.dashboardEnabled = dashboardEnabled;
        this.limit = limit;
        this.cursor = cursor;
    }

    public String getStatus() { return status; }
    public Boolean getDashboardEnabled() { return dashboardEnabled; }
    public int getLimit() { return limit; }
    public String getCursor() { return cursor; }
}
