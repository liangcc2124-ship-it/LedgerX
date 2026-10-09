package com.ledgerx.application.metrics;

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public final class FormulaTokenNormalizer {
    private static final Map<String, String> FUNCTIONS;

    static {
        Map<String, String> functions = new HashMap<>();
        alias(functions, "ADD", "add", "加", "相加");
        alias(functions, "SUB", "sub", "减", "相减");
        alias(functions, "MUL", "mul", "乘", "相乘");
        alias(functions, "DIV", "div", "除", "相除");
        alias(functions, "NEGATE", "negate", "取负", "负数");
        alias(functions, "MIN", "min", "最小值", "最小");
        alias(functions, "MAX", "max", "最大值", "最大");
        alias(functions, "AVG", "avg", "平均值", "平均");
        alias(functions, "ROUND", "round", "四舍五入", "舍入");
        alias(functions, "ABS", "abs", "绝对值");
        alias(functions, "CLAMP", "clamp", "限制范围", "限幅");
        alias(functions, "SAFE_DIVIDE", "safe_divide", "安全除法");
        FUNCTIONS = Collections.unmodifiableMap(functions);
    }

    private static void alias(Map<String, String> map, String canonical, String... aliases) {
        for (String alias : aliases) map.put(alias.toLowerCase(Locale.ROOT), canonical);
    }

    private FormulaTokenNormalizer() { }

    public static String normalizeFunction(String value) {
        if (value == null) throw new IllegalArgumentException("function is required");
        String normalized = FUNCTIONS.get(value.trim().toLowerCase(Locale.ROOT));
        if (normalized == null) throw new IllegalArgumentException("unsupported formula function: " + value);
        return normalized;
    }

    public static FormulaToken normalize(FormulaToken token) {
        if ("FUNCTION".equals(token.getType())) {
            return new FormulaToken(token.getType(), normalizeFunction(token.getValue()), token.getPath());
        }
        return token;
    }
}
