package com.ledgerx.application.metrics;

import java.time.LocalDate;

public interface DashboardApi {
    DashboardApiResult readDashboard(MetricGranularity granularity, LocalDate anchor) throws DashboardException;
    DashboardApiResult readLayout() throws DashboardException;
    DashboardApiResult replaceLayout(long expectedRevision, DashboardLayoutDraft draft, DashboardMutation mutation)
            throws DashboardException;
    DashboardApiResult resetLayout(long expectedRevision, DashboardMutation mutation) throws DashboardException;
}
