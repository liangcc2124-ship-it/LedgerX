package com.ledgerx.application.metrics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class MetricDraft {
    private final String id;
    private final String name;
    private final String description;
    private final String displayFormat;
    private final int precision;
    private final String periodBehavior;
    private final FormulaNode formula;
    private final List<FormulaToken> tokens;
    private final boolean hidden;
    private final boolean dashboardEnabled;

    public MetricDraft(String name, String description, String displayFormat, int precision, String periodBehavior,
            FormulaNode formula, List<FormulaToken> tokens, boolean hidden, boolean dashboardEnabled) {
        this(null, name, description, displayFormat, precision, periodBehavior, formula, tokens, hidden, dashboardEnabled);
    }

    public MetricDraft(String id, String name, String description, String displayFormat, int precision,
            String periodBehavior, FormulaNode formula, List<FormulaToken> tokens, boolean hidden,
            boolean dashboardEnabled) {
        this.id = id;
        this.name = name; this.description = description; this.displayFormat = displayFormat;
        this.precision = precision; this.periodBehavior = periodBehavior; this.formula = formula;
        this.tokens = Collections.unmodifiableList(new ArrayList<>(tokens == null ? Collections.emptyList() : tokens));
        this.hidden = hidden; this.dashboardEnabled = dashboardEnabled;
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getDisplayFormat() { return displayFormat; }
    public int getPrecision() { return precision; }
    public String getPeriodBehavior() { return periodBehavior; }
    public FormulaNode getFormula() { return formula; }
    public List<FormulaToken> getTokens() { return tokens; }
    public boolean isHidden() { return hidden; }
    public boolean isDashboardEnabled() { return dashboardEnabled; }
}
