package com.ledgerx.application.metrics;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Canonical SQLite representation with a tolerant reader for pre-P6 rows. */
public final class FormulaStorageMapper {
    private FormulaStorageMapper() { }

    public static Map<String, Object> toAst(FormulaNode node) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schemaVersion", 1);
        result.put("root", toNode(node));
        return result;
    }

    public static FormulaNode fromAst(JsonNode ast) {
        JsonNode root = unwrap(ast);
        if (root == null || root.isMissingNode() || root.isNull()) throw new IllegalArgumentException("formula root");
        return fromNode(root);
    }

    private static Map<String, Object> toNode(FormulaNode node) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("kind", node.getKind().name());
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

    private static FormulaNode fromNode(JsonNode raw) {
        JsonNode node = unwrap(raw);
        String kind = node.path("kind").asText("").trim().toUpperCase(Locale.ROOT);
        if ("OPERATION".equals(kind)) kind = node.path("operator").asText("").trim().toUpperCase(Locale.ROOT);
        if ("FUNCTION".equals(kind)) kind = node.path("function").asText("").trim().toUpperCase(Locale.ROOT);
        if ("SUBTRACT".equals(kind)) kind = "SUB";
        if ("MULTIPLY".equals(kind)) kind = "MUL";
        if ("DIVIDE".equals(kind)) kind = "DIV";
        if ("CONSTANT".equals(kind)) return FormulaNode.constant(node.path("value").asText());
        if ("REF".equals(kind) || "REFERENCE".equals(kind)) {
            FormulaReference.Kind referenceKind = FormulaReference.Kind.valueOf(
                    node.path("referenceKind").asText("").trim().toUpperCase(Locale.ROOT));
            return FormulaNode.reference(new FormulaReference(referenceKind, node.path("key").asText()));
        }
        JsonNode children = node.has("children") ? node.get("children")
                : node.has("arguments") ? node.get("arguments") : node.get("args");
        if (children == null || !children.isArray()) throw new IllegalArgumentException("formula children");
        List<FormulaNode> values = new ArrayList<>();
        for (JsonNode child : children) values.add(fromNode(child));
        return FormulaNode.operation(FormulaNode.Kind.valueOf(kind), values);
    }

    private static JsonNode unwrap(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return node;
        JsonNode current = node;
        while (current.isObject() && current.has("root") && !current.has("kind")) current = current.get("root");
        return current;
    }
}
