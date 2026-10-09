package com.ledgerx.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.ledgerx.application.metrics.FormulaNode;
import com.ledgerx.application.metrics.FormulaReference;
import com.ledgerx.application.metrics.MetricException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Maps the public formula AST contract to the transport-neutral domain model. */
public final class FormulaHttpMapper {
    private FormulaHttpMapper() { }

    public static FormulaNode parseAst(JsonNode ast) throws MetricException {
        if (ast == null || !ast.isObject() || ast.path("schemaVersion").asInt(-1) != 1
                || !ast.has("root")) {
            throw invalid("formula.ast", "公式 AST 无效。");
        }
        return parseNode(ast.get("root"), "formula.ast.root");
    }

    public static Map<String, Object> toAst(FormulaNode node) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schemaVersion", 1);
        result.put("root", toNode(node));
        return result;
    }

    private static Map<String, Object> toNode(FormulaNode node) {
        if (node == null) throw new IllegalArgumentException("formula node is required");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("kind", externalKind(node.getKind()));
        if (node.getKind() == FormulaNode.Kind.CONSTANT) {
            result.put("value", node.getConstant().toPlainString());
        } else if (node.getKind() == FormulaNode.Kind.REF) {
            result.put("referenceKind", node.getReference().getKind().name());
            result.put("key", node.getReference().getKey());
        } else {
            List<Map<String, Object>> children = new ArrayList<>();
            for (FormulaNode child : node.getChildren()) children.add(toNode(child));
            result.put("children", children);
        }
        return result;
    }

    private static FormulaNode parseNode(JsonNode node, String path) throws MetricException {
        if (node == null || !node.isObject()) throw invalid(path, "公式节点必须是对象。");
        String rawKind = text(node, "kind");
        if (rawKind == null) throw invalid(path + ".kind", "公式节点缺少 kind。");
        String kind = rawKind.trim().toUpperCase(Locale.ROOT);
        try {
            if ("CONSTANT".equals(kind)) {
                String value = text(node, "value");
                if (value == null || value.trim().isEmpty()) throw invalid(path + ".value", "常数不能为空。");
                return FormulaNode.constant(value);
            }
            if ("REF".equals(kind) || "REFERENCE".equals(kind)) {
                String refKind = text(node, "referenceKind");
                String key = text(node, "key");
                if (refKind == null || key == null || key.trim().isEmpty()) {
                    throw invalid(path + ".ref", "引用必须包含 referenceKind 和 key。");
                }
                return FormulaNode.reference(new FormulaReference(
                        FormulaReference.Kind.valueOf(refKind.trim().toUpperCase(Locale.ROOT)), key));
            }
            String operationName = kind;
            if ("OPERATION".equals(kind)) operationName = text(node, "operator");
            if ("FUNCTION".equals(kind)) operationName = text(node, "function");
            if (operationName == null || operationName.trim().isEmpty()) {
                throw invalid(path + ".kind", "公式操作缺少 operator/function。");
            }
            FormulaNode.Kind operation = internalKind(operationName);
            JsonNode children = node.has("children") ? node.get("children")
                    : node.has("arguments") ? node.get("arguments") : node.get("args");
            if (children == null || !children.isArray()) throw invalid(path + ".children", "公式参数必须是数组。");
            List<FormulaNode> values = new ArrayList<>();
            for (int i = 0; i < children.size(); i++) values.add(parseNode(children.get(i), path + ".children[" + i + "]"));
            return FormulaNode.operation(operation, values);
        } catch (MetricException ex) {
            throw ex;
        } catch (Exception ex) {
            throw invalid(path, "公式节点类型或参数无效。");
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isTextual() ? value.textValue() : null;
    }

    private static FormulaNode.Kind internalKind(String value) {
        String kind = value.trim().toUpperCase(Locale.ROOT);
        if ("SUBTRACT".equals(kind)) return FormulaNode.Kind.SUB;
        if ("MULTIPLY".equals(kind)) return FormulaNode.Kind.MUL;
        if ("DIVIDE".equals(kind)) return FormulaNode.Kind.DIV;
        return FormulaNode.Kind.valueOf(kind);
    }

    private static String externalKind(FormulaNode.Kind kind) {
        switch (kind) {
            case SUB: return "SUBTRACT";
            case MUL: return "MULTIPLY";
            case DIV: return "DIVIDE";
            default: return kind.name();
        }
    }

    private static MetricException invalid(String path, String message) {
        return new MetricException(400, "FORMULA_INVALID", "公式未通过校验。",
                Collections.singletonMap(path, message), Collections.emptyMap());
    }
}
