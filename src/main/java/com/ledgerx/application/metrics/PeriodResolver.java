package com.ledgerx.application.metrics;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

public final class PeriodResolver {
    private final Clock clock;

    public PeriodResolver(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public ResolvedPeriod resolve(DashboardPeriod requested) {
        Objects.requireNonNull(requested, "requested");
        LocalDate start = normalizeStart(requested.getGranularity(), requested.getAnchor());
        LocalDate endExclusive = nextStart(requested.getGranularity(), start);
        LocalDate previousStart = previousStart(requested.getGranularity(), start);
        LocalDate previousEndExclusive = start;
        LocalDate today = LocalDate.now(clock.withZone(ZoneId.of("Asia/Shanghai")));
        PeriodStatus status;
        LocalDate asOf;
        if (today.isBefore(start)) {
            status = PeriodStatus.FUTURE;
            asOf = null;
        } else if (today.isBefore(endExclusive)) {
            status = PeriodStatus.CURRENT;
            asOf = today;
        } else {
            status = PeriodStatus.HISTORICAL;
            asOf = endExclusive.minusDays(1);
        }

        LocalDate comparisonStart = previousStart;
        LocalDate comparisonEnd = previousEndExclusive;
        if (requested.getGranularity() != MetricGranularity.DAY
                && status == PeriodStatus.CURRENT) {
            long elapsedDays = ChronoUnit.DAYS.between(start, asOf) + 1;
            comparisonEnd = previousStart.plusDays(elapsedDays);
            if (comparisonEnd.isAfter(previousEndExclusive)) {
                comparisonEnd = previousEndExclusive;
            }
        }
        return new ResolvedPeriod(requested, start, endExclusive, asOf, previousStart,
                previousEndExclusive, comparisonStart, comparisonEnd, status);
    }

    private static LocalDate normalizeStart(MetricGranularity granularity, LocalDate anchor) {
        switch (granularity) {
            case DAY:
                return anchor;
            case WEEK:
                return anchor.minusDays(anchor.getDayOfWeek().getValue() - DayOfWeek.MONDAY.getValue());
            case MONTH:
                return anchor.withDayOfMonth(1);
            case YEAR:
                return anchor.withDayOfYear(1);
            default:
                throw new IllegalArgumentException("unsupported granularity");
        }
    }

    private static LocalDate nextStart(MetricGranularity granularity, LocalDate start) {
        switch (granularity) {
            case DAY: return start.plusDays(1);
            case WEEK: return start.plusWeeks(1);
            case MONTH: return start.plusMonths(1);
            case YEAR: return start.plusYears(1);
            default: throw new IllegalArgumentException("unsupported granularity");
        }
    }

    private static LocalDate previousStart(MetricGranularity granularity, LocalDate start) {
        switch (granularity) {
            case DAY: return start.minusDays(1);
            case WEEK: return start.minusWeeks(1);
            case MONTH: return start.minusMonths(1);
            case YEAR: return start.minusYears(1);
            default: throw new IllegalArgumentException("unsupported granularity");
        }
    }
}
