package com.ledgerx.application.metrics;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class FormulaNode {
    public enum Kind {
        CONSTANT,
        REF,
        ADD,
        SUB,
        MUL,
        DIV,
        NEGATE,
        MIN,
        MAX,
        AVG,
        ROUND,
        ABS,
        CLAMP,
        SAFE_DIVIDE
    }

    private final Kind kind;
    private final BigDecimal constant;
    private final FormulaReference reference;
    private final List<FormulaNode> children;

    private FormulaNode(Kind kind, BigDecimal constant, FormulaReference reference, List<FormulaNode> children) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.constant = constant;
        this.reference = reference;
        this.children = Collections.unmodifiableList(new ArrayList<>(children));
    }

    public static FormulaNode constant(String value) {
        if (value == null) throw new IllegalArgumentException("constant is required");
        return constant(new BigDecimal(value));
    }

    public static FormulaNode constant(BigDecimal value) {
        return new FormulaNode(Kind.CONSTANT, Objects.requireNonNull(value, "value"), null,
                Collections.emptyList());
    }

    public static FormulaNode reference(FormulaReference reference) {
        return new FormulaNode(Kind.REF, null, Objects.requireNonNull(reference, "reference"),
                Collections.emptyList());
    }

    public static FormulaNode operation(Kind kind, List<FormulaNode> children) {
        if (kind == Kind.CONSTANT || kind == Kind.REF) {
            throw new IllegalArgumentException("value nodes cannot be operations");
        }
        if (children == null) throw new IllegalArgumentException("children are required");
        return new FormulaNode(kind, null, null, children);
    }

    public Kind getKind() { return kind; }
    public BigDecimal getConstant() { return constant; }
    public FormulaReference getReference() { return reference; }
    public List<FormulaNode> getChildren() { return children; }
}
