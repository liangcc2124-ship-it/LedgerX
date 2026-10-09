package com.ledgerx.application.metrics;

import java.util.ArrayList;
import java.util.List;

/** Deterministically derives display tokens from the validated AST. */
public final class CanonicalFormulaTokens {
    private CanonicalFormulaTokens() { }

    public static List<FormulaToken> from(FormulaNode root) {
        List<FormulaToken> result = new ArrayList<>();
        visit(root, "formula.ast.root", result);
        return result;
    }

    private static void visit(FormulaNode node, String path, List<FormulaToken> result) {
        if (node.getKind() == FormulaNode.Kind.CONSTANT) {
            result.add(new FormulaToken("constant", node.getConstant().toPlainString(), path + ".value"));
            return;
        }
        if (node.getKind() == FormulaNode.Kind.REF) {
            result.add(new FormulaToken("reference", node.getReference().getKind().name() + ":" + node.getReference().getKey(), path));
            return;
        }
        if (isBinary(node.getKind())) {
            result.add(new FormulaToken("punctuation", "LPAREN", path));
            visit(node.getChildren().get(0), path + ".children[0]", result);
            result.add(new FormulaToken("operator", externalKind(node.getKind()), path + ".kind"));
            visit(node.getChildren().get(1), path + ".children[1]", result);
            result.add(new FormulaToken("punctuation", "RPAREN", path));
            return;
        }
        if (node.getKind() == FormulaNode.Kind.NEGATE) {
            result.add(new FormulaToken("operator", "NEGATE", path + ".kind"));
            visit(node.getChildren().get(0), path + ".children[0]", result);
            return;
        }
        result.add(new FormulaToken("function", externalKind(node.getKind()), path + ".kind"));
        result.add(new FormulaToken("punctuation", "LPAREN", path));
        for (int i = 0; i < node.getChildren().size(); i++) {
            if (i > 0) result.add(new FormulaToken("punctuation", "COMMA", path + ".children[" + i + "]"));
            visit(node.getChildren().get(i), path + ".children[" + i + "]", result);
        }
        result.add(new FormulaToken("punctuation", "RPAREN", path));
    }

    private static String externalKind(FormulaNode.Kind kind) {
        switch (kind) {
            case SUB: return "SUBTRACT";
            case MUL: return "MULTIPLY";
            case DIV: return "DIVIDE";
            default: return kind.name();
        }
    }

    private static boolean isBinary(FormulaNode.Kind kind) {
        return kind == FormulaNode.Kind.ADD || kind == FormulaNode.Kind.SUB
                || kind == FormulaNode.Kind.MUL || kind == FormulaNode.Kind.DIV;
    }
}
