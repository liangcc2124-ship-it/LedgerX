using LedgerX.Models;

namespace LedgerX.Services;

public sealed class FormulaEngine
{
    private const int MaxDepth = 32;
    private const int MaxNodes = 256;

    private static readonly HashSet<string> AllocationVariables =
    [
        "totalAmount", "totalDays", "overlapDays", "elapsedDays", "remainingDays"
    ];

    private static readonly HashSet<string> DepreciationVariables =
    [
        "cost", "salvageValue", "depreciableBase", "usefulLifeMonths", "elapsedMonths",
        "periodIndex", "bookValue", "totalUnits", "unitsUsed"
    ];

    private static readonly HashSet<string> SystemMetricIds =
    [
        "inflow", "outflow", "netflow", "fixedcost", "variablecost", "totalcost", "cash", "liquidity",
        "fixedassets", "assets", "netassets", "payables", "burn", "runway", "dependency", "chain"
    ];

    public FormulaExpression ParseTokens(IEnumerable<FormulaToken> input, FormulaScope scope)
    {
        var tokens = input?.ToList() ?? throw new ArgumentNullException(nameof(input));
        if (tokens.Count == 0) throw new BridgeException("formula_empty", "公式至少需要一个数据源或常量。", new Dictionary<string, string> { ["tokens"] = "公式不能为空。" });
        if (tokens.Count > MaxNodes) throw new BridgeException("formula_too_complex", $"公式最多包含 {MaxNodes} 个令牌。", new Dictionary<string, string> { ["tokens"] = "令牌过多。" });
        var expression = new StructuredTokenParser(tokens, value => ResolveSource(scope, value)).Parse();
        var errors = new List<string>(); var dependencies = new HashSet<string>(StringComparer.OrdinalIgnoreCase); var count = 0;
        ValidateNode(expression, scope, 0, ref count, errors, dependencies);
        if (errors.Count > 0) throw new BridgeException("formula_invalid", "结构化公式无效。", new Dictionary<string, string> { ["tokens"] = string.Join("；", errors) });
        return expression;
    }

    public FormulaValidationResult Compile(
        FormulaDefinition definition,
        IEnumerable<MetricDefinition>? metrics = null,
        IEnumerable<FormulaDefinition>? formulas = null,
        string? ownerMetricId = null)
    {
        ArgumentNullException.ThrowIfNull(definition);
        definition.RootExpression = ParseTokens(definition.Tokens, definition.Scope);
        var validation = Validate(definition, metrics, formulas, ownerMetricId);
        if (validation.IsValid) definition.Dependencies = [.. validation.Dependencies];
        return validation;
    }

    private static string ResolveSource(FormulaScope scope, string source)
    {
        var aliases = scope switch
        {
            FormulaScope.Metric => new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase)
            {
                ["记录金额合计"] = "base:recordAmount", ["现金流入"] = "metric:inflow", ["现金流出"] = "metric:outflow",
                ["已确认固定成本"] = "metric:fixedcost", ["已确认可变成本"] = "metric:variablecost", ["资金账户余额"] = "metric:cash",
                ["固定资产原值"] = "metric:fixedassets", ["累计折旧"] = "base:accumulatedDepreciation", ["资产净值"] = "metric:netassets", ["其他指标当前值"] = "base:metricValue"
            },
            FormulaScope.Allocation => new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase)
            {
                ["记录金额合计"] = "totalAmount", ["服务总天数"] = "totalDays", ["重叠天数"] = "overlapDays", ["已过天数"] = "elapsedDays", ["剩余天数"] = "remainingDays"
            },
            FormulaScope.Depreciation => new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase)
            {
                ["资产原值"] = "cost", ["残值"] = "salvageValue", ["可折旧金额"] = "depreciableBase", ["使用寿命（月）"] = "usefulLifeMonths", ["已使用月数"] = "elapsedMonths", ["期数"] = "periodIndex", ["账面价值"] = "bookValue", ["预计总工作量"] = "totalUnits", ["本期工作量"] = "unitsUsed"
            },
            _ => throw new BridgeException("formula_scope_invalid", "不支持的公式作用域。")
        };
        if (aliases.TryGetValue(source, out var variable)) return variable;
        if (IsAllowedVariable(scope, source)) return source;
        throw new BridgeException("formula_source_invalid", $"当前公式类型不支持数据源：{source}", new Dictionary<string, string> { ["tokens"] = "数据源不在白名单内。" });
    }

    private sealed class StructuredTokenParser(IReadOnlyList<FormulaToken> tokens, Func<string, string> resolveSource)
    {
        private int _index;
        public FormulaExpression Parse() { var expression = Additive(); if (_index != tokens.Count) throw Error("存在未预期的令牌。"); return expression; }
        private FormulaExpression Additive() { var expression = Multiplicative(); while (Match("+", "-", "−")) { var op = tokens[_index - 1].Value; expression = FormulaExpression.Op(op == "+" ? FormulaNodeType.Add : FormulaNodeType.Subtract, expression, Multiplicative()); } return expression; }
        private FormulaExpression Multiplicative() { var expression = Unary(); while (Match("*", "×", "/", "÷")) { var op = tokens[_index - 1].Value; expression = FormulaExpression.Op(op is "*" or "×" ? FormulaNodeType.Multiply : FormulaNodeType.Divide, expression, Unary()); } return expression; }
        private FormulaExpression Unary() => Match("-", "−") ? FormulaExpression.Op(FormulaNodeType.Negate, Unary()) : Primary();
        private FormulaExpression Primary()
        {
            if (Match("(")) { var expression = Additive(); Consume(")", "括号未闭合。"); return expression; }
            if (_index >= tokens.Count) throw Error("公式不能以运算符结束。");
            var token = tokens[_index++];
            if (token.Kind == FormulaTokenKind.Source) return FormulaExpression.Ref(resolveSource(token.Value));
            if (token.Kind == FormulaTokenKind.Constant && decimal.TryParse(token.Value, System.Globalization.NumberStyles.Number, System.Globalization.CultureInfo.InvariantCulture, out var value)) return FormulaExpression.Value(value);
            if (token.Kind == FormulaTokenKind.Function) return ParseFunction(token.Value);
            throw Error("此处需要数据源、常量、函数或左括号。");
        }
        private FormulaExpression ParseFunction(string name)
        {
            Consume("(", "函数后必须紧跟左括号。");
            var arguments = new List<FormulaExpression> { Additive() };
            while (Match(",", "，")) arguments.Add(Additive());
            Consume(")", "函数参数缺少右括号。");
            return name.ToUpperInvariant() switch
            {
                "SUM" => arguments.Count >= 2 ? Fold(FormulaNodeType.Add, arguments) : throw Error("SUM 至少需要两个参数。"),
                "AVG" or "AVERAGE" => FormulaExpression.Op(FormulaNodeType.Average, [.. arguments]),
                "ROUND" => arguments.Count is 1 or 2 ? FormulaExpression.Op(FormulaNodeType.Round, [.. arguments]) : throw Error("ROUND 需要一或两个参数。"),
                "ABS" => arguments.Count == 1 ? FormulaExpression.Op(FormulaNodeType.Abs, arguments[0]) : throw Error("ABS 需要一个参数。"),
                "MIN" => arguments.Count >= 2 ? Fold(FormulaNodeType.Min, arguments) : throw Error("MIN 至少需要两个参数。"),
                "MAX" => arguments.Count >= 2 ? Fold(FormulaNodeType.Max, arguments) : throw Error("MAX 至少需要两个参数。"),
                "CLAMP" => arguments.Count == 3 ? FormulaExpression.Op(FormulaNodeType.Clamp, [.. arguments]) : throw Error("CLAMP 需要三个参数。"),
                _ => throw Error($"不支持函数：{name}")
            };
        }
        private static FormulaExpression Fold(FormulaNodeType type, IReadOnlyList<FormulaExpression> arguments) =>
            arguments.Skip(1).Aggregate(arguments[0], (current, next) => FormulaExpression.Op(type, current, next));
        private bool Match(params string[] values) { if (_index < tokens.Count && values.Contains(tokens[_index].Value, StringComparer.Ordinal)) { _index++; return true; } return false; }
        private void Consume(string value, string message) { if (!Match(value)) throw Error(message); }
        private BridgeException Error(string message) => new("formula_tokens_invalid", message, new Dictionary<string, string> { ["tokens"] = $"第 {_index + 1} 个令牌：{message}" });
    }
    public FormulaValidationResult Validate(
        FormulaDefinition definition,
        IEnumerable<MetricDefinition>? metrics = null,
        IEnumerable<FormulaDefinition>? formulas = null,
        string? ownerMetricId = null)
    {
        var errors = new List<string>();
        var dependencies = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        var nodeCount = 0;
        ValidateNode(definition.RootExpression, definition.Scope, 0, ref nodeCount, errors, dependencies);

        if (definition.Scope == FormulaScope.Metric && metrics is not null)
        {
            var ids = (metrics ?? []).Select(x => x.Id).ToHashSet(StringComparer.OrdinalIgnoreCase);
            foreach (var dependency in dependencies.Where(x => x.StartsWith("metric:", StringComparison.OrdinalIgnoreCase)))
            {
                var id = dependency["metric:".Length..];
                if (!ids.Contains(id) && !SystemMetricIds.Contains(id))
                    errors.Add($"指标引用不存在：{id}");
            }

            if (!string.IsNullOrWhiteSpace(ownerMetricId) &&
                HasCycle(ownerMetricId, dependencies, metrics ?? [], formulas ?? []))
            {
                errors.Add("公式会形成循环引用。");
            }
        }

        return new FormulaValidationResult(errors.Count == 0, errors, dependencies.Order().ToList());
    }

    public decimal Evaluate(FormulaExpression expression, IReadOnlyDictionary<string, decimal> variables)
    {
        var nodeCount = 0;
        return EvaluateNode(expression, variables, 0, ref nodeCount);
    }

    public decimal Evaluate(
        FormulaDefinition definition,
        IReadOnlyDictionary<string, decimal> variables,
        IEnumerable<MetricDefinition>? metrics = null,
        IEnumerable<FormulaDefinition>? formulas = null,
        string? ownerMetricId = null)
    {
        var validation = Validate(definition, metrics, formulas, ownerMetricId);
        if (!validation.IsValid)
            throw new BridgeException("formula_invalid", "公式无效。", new Dictionary<string, string> { ["rootExpression"] = string.Join("；", validation.Errors) });
        return Evaluate(definition.RootExpression, variables);
    }

    private static void ValidateNode(
        FormulaExpression? node,
        FormulaScope scope,
        int depth,
        ref int nodeCount,
        List<string> errors,
        HashSet<string> dependencies)
    {
        if (node is null)
        {
            errors.Add("公式节点不能为空。");
            return;
        }
        if (++nodeCount > MaxNodes)
        {
            errors.Add($"公式最多包含 {MaxNodes} 个节点。");
            return;
        }
        if (depth > MaxDepth)
        {
            errors.Add($"公式嵌套不能超过 {MaxDepth} 层。");
            return;
        }

        var args = node.Arguments ?? [];
        switch (node.NodeType)
        {
            case FormulaNodeType.Constant:
                if (node.Constant is null) errors.Add("常量节点缺少数值。");
                if (args.Count != 0) errors.Add("常量节点不能包含子节点。");
                break;
            case FormulaNodeType.Variable:
                if (string.IsNullOrWhiteSpace(node.Variable))
                {
                    errors.Add("变量节点缺少变量名。");
                    break;
                }
                if (!IsAllowedVariable(scope, node.Variable))
                    errors.Add($"当前公式类型不允许变量：{node.Variable}");
                else
                    dependencies.Add(node.Variable);
                if (args.Count != 0) errors.Add("变量节点不能包含子节点。");
                break;
            case FormulaNodeType.Negate:
                RequireArity(node.NodeType, args, 1, errors);
                break;
            case FormulaNodeType.Add:
            case FormulaNodeType.Subtract:
            case FormulaNodeType.Multiply:
            case FormulaNodeType.Divide:
            case FormulaNodeType.Min:
            case FormulaNodeType.Max:
                RequireArity(node.NodeType, args, 2, errors);
                break;
            case FormulaNodeType.Round:
                if (args.Count is < 1 or > 2) errors.Add("Round 需要数值和可选的小数位参数。");
                break;
            case FormulaNodeType.Abs:
                RequireArity(node.NodeType, args, 1, errors);
                break;
            case FormulaNodeType.Clamp:
                RequireArity(node.NodeType, args, 3, errors);
                break;
            case FormulaNodeType.Average:
                if (args.Count == 0) errors.Add("Average 至少需要一个参数。");
                break;
            default:
                errors.Add($"不支持的公式节点：{node.NodeType}");
                break;
        }

        foreach (var argument in args)
            ValidateNode(argument, scope, depth + 1, ref nodeCount, errors, dependencies);
    }

    private static decimal EvaluateNode(
        FormulaExpression node,
        IReadOnlyDictionary<string, decimal> variables,
        int depth,
        ref int nodeCount)
    {
        if (++nodeCount > MaxNodes || depth > MaxDepth)
            throw new BridgeException("formula_too_complex", "公式超出允许的复杂度。");

        return node.NodeType switch
        {
            FormulaNodeType.Constant => node.Constant ?? throw new BridgeException("formula_invalid", "常量节点缺少数值。"),
            FormulaNodeType.Variable => variables.TryGetValue(node.Variable ?? string.Empty, out var value)
                ? value
                : throw new BridgeException("formula_missing_variable", $"缺少公式变量：{node.Variable}"),
            FormulaNodeType.Add => EvaluateArgument(node, 0, variables, depth, ref nodeCount) + EvaluateArgument(node, 1, variables, depth, ref nodeCount),
            FormulaNodeType.Subtract => EvaluateArgument(node, 0, variables, depth, ref nodeCount) - EvaluateArgument(node, 1, variables, depth, ref nodeCount),
            FormulaNodeType.Multiply => EvaluateArgument(node, 0, variables, depth, ref nodeCount) * EvaluateArgument(node, 1, variables, depth, ref nodeCount),
            FormulaNodeType.Abs => Math.Abs(EvaluateArgument(node, 0, variables, depth, ref nodeCount)),
            FormulaNodeType.Clamp => Math.Clamp(EvaluateArgument(node, 0, variables, depth, ref nodeCount), EvaluateArgument(node, 1, variables, depth, ref nodeCount), EvaluateArgument(node, 2, variables, depth, ref nodeCount)),
            FormulaNodeType.Average => Average(node, variables, depth, ref nodeCount),
            FormulaNodeType.Divide => Divide(EvaluateArgument(node, 0, variables, depth, ref nodeCount), EvaluateArgument(node, 1, variables, depth, ref nodeCount)),
            FormulaNodeType.Negate => -EvaluateArgument(node, 0, variables, depth, ref nodeCount),
            FormulaNodeType.Min => Math.Min(EvaluateArgument(node, 0, variables, depth, ref nodeCount), EvaluateArgument(node, 1, variables, depth, ref nodeCount)),
            FormulaNodeType.Max => Math.Max(EvaluateArgument(node, 0, variables, depth, ref nodeCount), EvaluateArgument(node, 1, variables, depth, ref nodeCount)),
            FormulaNodeType.Round => Math.Round(
                EvaluateArgument(node, 0, variables, depth, ref nodeCount),
                node.Arguments.Count == 2 ? Decimal.ToInt32(EvaluateArgument(node, 1, variables, depth, ref nodeCount)) : 2,
                MidpointRounding.AwayFromZero),
            _ => throw new BridgeException("formula_invalid", $"不支持的公式节点：{node.NodeType}")
        };
    }

    private static decimal Average(FormulaExpression node, IReadOnlyDictionary<string, decimal> variables, int depth, ref int nodeCount)
    {
        decimal total = 0m;
        foreach (var argument in node.Arguments) total += EvaluateNode(argument, variables, depth + 1, ref nodeCount);
        return total / node.Arguments.Count;
    }

    private static decimal EvaluateArgument(
        FormulaExpression node,
        int index,
        IReadOnlyDictionary<string, decimal> variables,
        int depth,
        ref int nodeCount) =>
        EvaluateNode(node.Arguments[index], variables, depth + 1, ref nodeCount);

    private static decimal Divide(decimal dividend, decimal divisor)
    {
        if (divisor == 0) throw new BridgeException("formula_divide_by_zero", "公式发生除零。", recoverySuggestion: "为分母增加非零保护，或使用 Max 设置下限。");
        return dividend / divisor;
    }

    private static bool IsAllowedVariable(FormulaScope scope, string variable) => scope switch
    {
        FormulaScope.Allocation => AllocationVariables.Contains(variable),
        FormulaScope.Depreciation => DepreciationVariables.Contains(variable),
        FormulaScope.Metric => variable.StartsWith("metric:", StringComparison.OrdinalIgnoreCase)
            || variable.StartsWith("base:", StringComparison.OrdinalIgnoreCase)
            || variable.StartsWith("direct:", StringComparison.OrdinalIgnoreCase),
        _ => false
    };

    private static void RequireArity(FormulaNodeType type, IReadOnlyCollection<FormulaExpression> args, int expected, ICollection<string> errors)
    {
        if (args.Count != expected) errors.Add($"{type} 需要 {expected} 个参数。");
    }

    private static bool HasCycle(
        string ownerMetricId,
        IEnumerable<string> candidateDependencies,
        IEnumerable<MetricDefinition> metrics,
        IEnumerable<FormulaDefinition> formulas)
    {
        var formulaById = formulas.ToDictionary(x => x.Id);
        var graph = new Dictionary<string, List<string>>(StringComparer.OrdinalIgnoreCase);
        foreach (var metric in metrics)
        {
            if (!formulaById.TryGetValue(metric.FormulaId, out var formula)) continue;
            graph[metric.Id] = formula.Dependencies
                .Where(x => x.StartsWith("metric:", StringComparison.OrdinalIgnoreCase))
                .Select(x => x["metric:".Length..])
                .ToList();
        }
        graph[ownerMetricId] = candidateDependencies
            .Where(x => x.StartsWith("metric:", StringComparison.OrdinalIgnoreCase))
            .Select(x => x["metric:".Length..])
            .ToList();

        var visiting = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        var visited = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        bool Visit(string id)
        {
            if (!visiting.Add(id)) return true;
            if (!visited.Add(id)) { visiting.Remove(id); return false; }
            if (graph.TryGetValue(id, out var next) && next.Any(Visit)) return true;
            visiting.Remove(id);
            return false;
        }
        return Visit(ownerMetricId);
    }
}

public static class MetricTemplateCatalog
{
    public static IReadOnlyList<MetricTemplate> All { get; } =
    [
        new("savings-rate", "储蓄率", "净现金流占现金流入的比例", MetricDisplayFormat.Percent, MetricPeriodBehavior.Period,
            FormulaExpression.Op(FormulaNodeType.Multiply,
                FormulaExpression.Op(FormulaNodeType.Divide, FormulaExpression.Ref("metric:netflow"), FormulaExpression.Ref("metric:inflow")),
                FormulaExpression.Value(100))),
        new("expense-ratio", "支出率", "现金流出占现金流入的比例", MetricDisplayFormat.Percent, MetricPeriodBehavior.Period,
            FormulaExpression.Op(FormulaNodeType.Multiply,
                FormulaExpression.Op(FormulaNodeType.Divide, FormulaExpression.Ref("metric:outflow"), FormulaExpression.Ref("metric:inflow")),
                FormulaExpression.Value(100))),
        new("fixed-cost-share", "固定成本占比", "固定成本占总成本的比例", MetricDisplayFormat.Percent, MetricPeriodBehavior.Period,
            FormulaExpression.Op(FormulaNodeType.Multiply,
                FormulaExpression.Op(FormulaNodeType.Divide, FormulaExpression.Ref("metric:fixedcost"), FormulaExpression.Ref("metric:totalcost")),
                FormulaExpression.Value(100))),
        new("debt-ratio", "负债率", "应付账款占总资产的比例", MetricDisplayFormat.Percent, MetricPeriodBehavior.PointInTime,
            FormulaExpression.Op(FormulaNodeType.Multiply,
                FormulaExpression.Op(FormulaNodeType.Divide, FormulaExpression.Ref("metric:payables"), FormulaExpression.Ref("metric:assets")),
                FormulaExpression.Value(100))),
        new("liquid-net-worth", "流动净值", "可用现金扣除应付款", MetricDisplayFormat.Currency, MetricPeriodBehavior.PointInTime,
            FormulaExpression.Op(FormulaNodeType.Subtract, FormulaExpression.Ref("metric:liquidity"), FormulaExpression.Ref("metric:payables"))),
        new("monthly-fixed-cost", "月均固定成本", "所选期间固定成本折算的月均值", MetricDisplayFormat.Currency, MetricPeriodBehavior.Period,
            FormulaExpression.Ref("metric:fixedcost")),
        new("direct-amortization", "摊销指标", "由记录直接归集的摊销值", MetricDisplayFormat.Currency, MetricPeriodBehavior.Period,
            FormulaExpression.Ref("direct:self"), true),
        new("blank", "空白指标", "从空白结构化公式开始", MetricDisplayFormat.Currency, MetricPeriodBehavior.Period,
            FormulaExpression.Value(0), true)
    ];
}
