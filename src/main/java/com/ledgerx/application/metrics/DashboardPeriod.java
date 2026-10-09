package com.ledgerx.application.metrics;

import java.time.LocalDate;
import java.util.Objects;

public final class DashboardPeriod {
    private final MetricGranularity granularity;
    private final LocalDate anchor;

    public DashboardPeriod(MetricGranularity granularity, LocalDate anchor) {
        this.granularity = Objects.requireNonNull(granularity, "granularity");
        this.anchor = Objects.requireNonNull(anchor, "anchor");
    }

    public MetricGranularity getGranularity() {
        return granularity;
    }

    public LocalDate getAnchor() {
        return anchor;
    }
}
