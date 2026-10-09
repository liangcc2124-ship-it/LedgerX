package com.ledgerx.application.metrics;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

public final class FormulaEvaluator {
    private static final MathContext CONTEXT = MathContext.DECIMAL128;

    public FormulaEvaluationResult evaluate(FormulaNode node, FormulaEvaluationContext context) {
        if (node == null || context == null) return FormulaEvaluationResult.unavailable();
        switch (node.getKind()) {
            case CONSTANT:
                return FormulaEvaluationResult.ready(node.getConstant());
            case REF:
                BigDecimal value = context.resolve(node.getReference());
                return value == null ? FormulaEvaluationResult.unavailable() : FormulaEvaluationResult.ready(value);
            case NEGATE:
                return unary(node, context, valueOf -> valueOf.negate(CONTEXT));
            case ABS:
                return unary(node, context, valueOf -> valueOf.abs(CONTEXT));
            case ADD:
                return binary(node, context, (left, right) -> left.add(right, CONTEXT));
            case SUB:
                return binary(node, context, (left, right) -> left.subtract(right, CONTEXT));
            case MUL:
                return binary(node, context, (left, right) -> left.multiply(right, CONTEXT));
            case DIV:
                return divide(node, context, false);
            case SAFE_DIVIDE:
                return divide(node, context, true);
            case MIN:
                return aggregate(node, context, false);
            case MAX:
                return aggregate(node, context, true);
            case AVG:
                return average(node, context);
            case ROUND:
                return round(node, context);
            case CLAMP:
                return clamp(node, context);
            default:
                return FormulaEvaluationResult.unavailable();
        }
    }

    private FormulaEvaluationResult unary(FormulaNode node, FormulaEvaluationContext context,
            UnaryOperation operation) {
        FormulaEvaluationResult result = evaluate(node.getChildren().get(0), context);
        return result.isReady() ? FormulaEvaluationResult.ready(operation.apply(result.getValue())) : result;
    }

    private FormulaEvaluationResult binary(FormulaNode node, FormulaEvaluationContext context,
            BinaryOperation operation) {
        FormulaEvaluationResult left = evaluate(node.getChildren().get(0), context);
        FormulaEvaluationResult right = evaluate(node.getChildren().get(1), context);
        if (!left.isReady()) return left;
        if (!right.isReady()) return right;
        return FormulaEvaluationResult.ready(operation.apply(left.getValue(), right.getValue()));
    }

    private FormulaEvaluationResult divide(FormulaNode node, FormulaEvaluationContext context, boolean safe) {
        FormulaEvaluationResult left = evaluate(node.getChildren().get(0), context);
        FormulaEvaluationResult right = evaluate(node.getChildren().get(1), context);
        if (!left.isReady()) return left;
        if (!right.isReady()) return right;
        if (right.getValue().signum() == 0) {
            if (safe && node.getChildren().size() == 3) return evaluate(node.getChildren().get(2), context);
            return FormulaEvaluationResult.notComputable();
        }
        return FormulaEvaluationResult.ready(left.getValue().divide(right.getValue(), CONTEXT));
    }

    private FormulaEvaluationResult aggregate(FormulaNode node, FormulaEvaluationContext context, boolean max) {
        BigDecimal result = null;
        for (FormulaNode child : node.getChildren()) {
            FormulaEvaluationResult value = evaluate(child, context);
            if (!value.isReady()) return value;
            result = result == null ? value.getValue()
                    : (max ? result.max(value.getValue()) : result.min(value.getValue()));
        }
        return result == null ? FormulaEvaluationResult.notComputable() : FormulaEvaluationResult.ready(result);
    }

    private FormulaEvaluationResult average(FormulaNode node, FormulaEvaluationContext context) {
        BigDecimal total = BigDecimal.ZERO;
        for (FormulaNode child : node.getChildren()) {
            FormulaEvaluationResult value = evaluate(child, context);
            if (!value.isReady()) return value;
            total = total.add(value.getValue(), CONTEXT);
        }
        return node.getChildren().isEmpty() ? FormulaEvaluationResult.notComputable()
                : FormulaEvaluationResult.ready(total.divide(BigDecimal.valueOf(node.getChildren().size()), CONTEXT));
    }

    private FormulaEvaluationResult round(FormulaNode node, FormulaEvaluationContext context) {
        FormulaEvaluationResult value = evaluate(node.getChildren().get(0), context);
        if (!value.isReady()) return value;
        int places;
        if (node.getChildren().size() == 1) {
            places = 0;
        } else {
            FormulaEvaluationResult scale = evaluate(node.getChildren().get(1), context);
            if (!scale.isReady()) return scale;
            try {
                places = scale.getValue().intValueExact();
            } catch (ArithmeticException ex) {
                return FormulaEvaluationResult.notComputable();
            }
        }
        return FormulaEvaluationResult.ready(value.getValue().setScale(places, RoundingMode.HALF_UP));
    }

    private FormulaEvaluationResult clamp(FormulaNode node, FormulaEvaluationContext context) {
        FormulaEvaluationResult value = evaluate(node.getChildren().get(0), context);
        FormulaEvaluationResult minimum = evaluate(node.getChildren().get(1), context);
        FormulaEvaluationResult maximum = evaluate(node.getChildren().get(2), context);
        if (!value.isReady()) return value;
        if (!minimum.isReady()) return minimum;
        if (!maximum.isReady()) return maximum;
        return FormulaEvaluationResult.ready(value.getValue().max(minimum.getValue()).min(maximum.getValue()));
    }

    private interface UnaryOperation { BigDecimal apply(BigDecimal value); }
    private interface BinaryOperation { BigDecimal apply(BigDecimal left, BigDecimal right); }
}
