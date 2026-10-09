package com.ledgerx.application.metrics;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Closed, transport-neutral input shared by formula validate and preview. */
public final class FormulaDraftRequest {
    private final String candidateMetricId;
    private final String displayFormat;
    private final FormulaNode formula;
    private final List<FormulaToken> tokens;
    private final MetricGranularity granularity;
    private final LocalDate anchor;

    public FormulaDraftRequest(String candidateMetricId, String displayFormat, FormulaNode formula,
            List<FormulaToken> tokens, MetricGranularity granularity, LocalDate anchor) {
        this.candidateMetricId = candidateMetricId;
        this.displayFormat = displayFormat;
        this.formula = formula;
        this.tokens = Collections.unmodifiableList(new ArrayList<>(tokens == null
                ? Collections.emptyList() : tokens));
        this.granularity = granularity;
        this.anchor = anchor;
    }

    public String getCandidateMetricId() { return candidateMetricId; }
    public String getDisplayFormat() { return displayFormat; }
    public FormulaNode getFormula() { return formula; }
    public List<FormulaToken> getTokens() { return tokens; }
    public MetricGranularity getGranularity() { return granularity; }
    public LocalDate getAnchor() { return anchor; }
}
