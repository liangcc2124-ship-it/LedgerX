package com.ledgerx.application.metrics;

import java.time.LocalDate;
import java.util.Objects;

public final class ResolvedPeriod {
    private final DashboardPeriod requested;
    private final LocalDate start;
    private final LocalDate endExclusive;
    private final LocalDate asOf;
    private final LocalDate previousStart;
    private final LocalDate previousEndExclusive;
    private final LocalDate comparisonStart;
    private final LocalDate comparisonEndExclusive;
    private final PeriodStatus status;

    public ResolvedPeriod(DashboardPeriod requested, LocalDate start, LocalDate endExclusive,
            LocalDate asOf, LocalDate previousStart, LocalDate previousEndExclusive,
            LocalDate comparisonStart, LocalDate comparisonEndExclusive, PeriodStatus status) {
        this.requested = Objects.requireNonNull(requested, "requested");
        this.start = Objects.requireNonNull(start, "start");
        this.endExclusive = Objects.requireNonNull(endExclusive, "endExclusive");
        this.asOf = asOf;
        this.previousStart = Objects.requireNonNull(previousStart, "previousStart");
        this.previousEndExclusive = Objects.requireNonNull(previousEndExclusive, "previousEndExclusive");
        this.comparisonStart = Objects.requireNonNull(comparisonStart, "comparisonStart");
        this.comparisonEndExclusive = Objects.requireNonNull(comparisonEndExclusive, "comparisonEndExclusive");
        this.status = Objects.requireNonNull(status, "status");
    }

    public DashboardPeriod getRequested() { return requested; }
    public LocalDate getStart() { return start; }
    public LocalDate getEndExclusive() { return endExclusive; }
    public LocalDate getAsOf() { return asOf; }
    public LocalDate getPreviousStart() { return previousStart; }
    public LocalDate getPreviousEndExclusive() { return previousEndExclusive; }
    public LocalDate getComparisonStart() { return comparisonStart; }
    public LocalDate getComparisonEndExclusive() { return comparisonEndExclusive; }
    public PeriodStatus getStatus() { return status; }
}
