package com.ledgerx.application.metrics;

import com.ledgerx.persistence.LedgerMetricsRepository.LayoutItemRecord;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class DashboardLayoutDraft {
    private final List<LayoutItemRecord> items;

    public DashboardLayoutDraft(List<LayoutItemRecord> items) {
        this.items = Collections.unmodifiableList(new ArrayList<>(items == null ? Collections.emptyList() : items));
    }

    public List<LayoutItemRecord> getItems() { return items; }
}
