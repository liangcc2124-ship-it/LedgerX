package com.ledgerx.application.metrics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class FormulaValidationResult {
    private final List<FormulaError> errors;
    private final Set<FormulaReference> dependencies;

    public FormulaValidationResult(List<FormulaError> errors, Set<FormulaReference> dependencies) {
        this.errors = Collections.unmodifiableList(new ArrayList<>(errors));
        this.dependencies = Collections.unmodifiableSet(new LinkedHashSet<>(dependencies));
    }

    public boolean isValid() { return errors.isEmpty(); }
    public List<FormulaError> getErrors() { return errors; }
    public Set<FormulaReference> getDependencies() { return dependencies; }
}
