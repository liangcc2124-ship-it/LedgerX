package com.ledgerx.application.metrics;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class FormulaValidator {
    public static final int MAX_DEPTH = 32;
    public static final int MAX_NODES = 256;
    public static final int MAX_FUNCTION_ARGS = 32;
    private static final Set<String> TIME_REFERENCES;

    static {
        Set<String> values = new HashSet<>();
        values.add("period-days");
        values.add("elapsed-days");
        values.add("complete-months");
        TIME_REFERENCES = Collections.unmodifiableSet(values);
    }

    public FormulaValidationResult validate(FormulaNode root, String ownerMetricId,
            Set<String> knownMetricIds, Map<String, Set<String>> existingGraph) {
        List<FormulaError> errors = new ArrayList<>();
        Set<FormulaReference> dependencies = new LinkedHashSet<>();
        if (root == null) {
            errors.add(error("FORMULA_INVALID", "formula.ast", "公式不能为空。"));
            return new FormulaValidationResult(errors, dependencies);
        }
        Set<String> known = knownMetricIds == null ? Collections.emptySet() : knownMetricIds;
        visit(root, "formula.ast", 1, new Counter(), ownerMetricId, known, dependencies, errors);
        Set<String> metricDependencies = new LinkedHashSet<>();
        for (FormulaReference reference : dependencies) {
            if (reference.getKind() == FormulaReference.Kind.METRIC) {
                metricDependencies.add(reference.getKey());
            }
        }
        if (ownerMetricId != null && metricDependencies.contains(ownerMetricId)) {
            errors.add(error("FORMULA_CYCLE", "formula.ast", "指标不能引用自身。"));
        } else if (ownerMetricId != null && !metricDependencies.isEmpty()
                && createsCycle(ownerMetricId, metricDependencies, existingGraph)) {
            errors.add(error("FORMULA_CYCLE", "formula.ast", "指标依赖图存在循环。"));
        }
        return new FormulaValidationResult(errors, dependencies);
    }

    public FormulaValidationResult validate(FormulaNode root, String ownerMetricId, Set<String> knownMetricIds) {
        return validate(root, ownerMetricId, knownMetricIds, Collections.emptyMap());
    }

    private void visit(FormulaNode node, String path, int depth, Counter counter, String ownerMetricId,
            Set<String> knownMetricIds, Set<FormulaReference> dependencies, List<FormulaError> errors) {
        if (node == null) {
            errors.add(error("FORMULA_INVALID", path, "公式节点不能为空。"));
            return;
        }
        counter.nodes++;
        if (counter.nodes > MAX_NODES) {
            errors.add(error("FORMULA_TOO_LARGE", path, "公式节点数不能超过 256。"));
            return;
        }
        if (depth > MAX_DEPTH) {
            errors.add(error("FORMULA_TOO_DEEP", path, "公式嵌套深度不能超过 32。"));
            return;
        }
        switch (node.getKind()) {
            case CONSTANT:
                if (node.getConstant() == null || node.getConstant().precision() > 32) {
                    errors.add(error("FORMULA_INVALID", path + ".value", "常数必须是有限十进制值。"));
                }
                break;
            case REF:
                validateReference(node.getReference(), path, ownerMetricId, knownMetricIds, dependencies, errors);
                break;
            default:
                validateArity(node, path, errors);
                for (int index = 0; index < node.getChildren().size(); index++) {
                    visit(node.getChildren().get(index), path + ".args[" + index + "]", depth + 1,
                            counter, ownerMetricId, knownMetricIds, dependencies, errors);
                }
                if (node.getKind() == FormulaNode.Kind.ROUND && node.getChildren().size() == 2) {
                    FormulaNode scale = node.getChildren().get(1);
                    if (scale.getKind() != FormulaNode.Kind.CONSTANT || scale.getConstant() == null
                            || scale.getConstant().scale() > 0 || scale.getConstant().compareTo(BigDecimal.ZERO) < 0
                            || scale.getConstant().compareTo(BigDecimal.valueOf(8)) > 0) {
                        errors.add(error("FORMULA_INVALID", path + ".args[1]", "舍入位数必须是 0 到 8 的整数。"));
                    }
                }
        }
    }

    private void validateReference(FormulaReference reference, String path, String ownerMetricId,
            Set<String> knownMetricIds, Set<FormulaReference> dependencies, List<FormulaError> errors) {
        if (reference == null) {
            errors.add(error("FORMULA_INVALID", path, "引用不能为空。"));
            return;
        }
        dependencies.add(reference);
        switch (reference.getKind()) {
            case METRIC:
                if (reference.getKey().trim().isEmpty() || !knownMetricIds.contains(reference.getKey())) {
                    errors.add(error("REFERENCE_CONFLICT", path + ".ref", "引用的指标不存在或不可用。"));
                }
                if (ownerMetricId != null && ownerMetricId.equals(reference.getKey())) {
                    errors.add(error("FORMULA_CYCLE", path + ".ref", "指标不能引用自身。"));
                }
                break;
            case TIME:
                if (!TIME_REFERENCES.contains(reference.getKey())) {
                    errors.add(error("FORMULA_INVALID", path + ".ref", "时间变量不受支持。"));
                }
                break;
            case CATEGORY_INCOME:
            case CATEGORY_EXPENSE:
            case ACCOUNT_BALANCE:
                try {
                    UUID.fromString(reference.getKey());
                } catch (IllegalArgumentException ex) {
                    errors.add(error("FORMULA_INVALID", path + ".ref", "分类或账户引用必须是 UUID。"));
                }
                break;
            default:
                errors.add(error("FORMULA_INVALID", path + ".ref", "引用类型不受支持。"));
        }
    }

    private static void validateArity(FormulaNode node, String path, List<FormulaError> errors) {
        int count = node.getChildren().size();
        boolean valid;
        switch (node.getKind()) {
            case ADD: case SUB: case MUL: case DIV: valid = count == 2; break;
            case NEGATE: case ABS: valid = count == 1; break;
            case MIN: case MAX: case AVG: valid = count >= 2 && count <= MAX_FUNCTION_ARGS; break;
            case ROUND: valid = count == 1 || count == 2; break;
            case SAFE_DIVIDE: valid = count == 2; break;
            case CLAMP: valid = count == 3; break;
            default: valid = false;
        }
        if (!valid) errors.add(error("FORMULA_INVALID", path, "函数参数数量不符合要求。"));
    }

    private static boolean createsCycle(String owner, Set<String> dependencies, Map<String, Set<String>> graph) {
        for (String dependency : dependencies) {
            if (reaches(dependency, owner, graph, new HashSet<>())) return true;
        }
        return false;
    }

    private static boolean reaches(String current, String target, Map<String, Set<String>> graph, Set<String> visited) {
        if (!visited.add(current)) return false;
        Set<String> next = graph == null ? null : graph.get(current);
        if (next == null) return false;
        if (next.contains(target)) return true;
        for (String value : next) if (reaches(value, target, graph, visited)) return true;
        return false;
    }

    private static FormulaError error(String code, String path, String message) {
        return new FormulaError(code, path, message);
    }

    private static final class Counter {
        private int nodes;
    }
}
