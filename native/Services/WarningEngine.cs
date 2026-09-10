using LedgerX.Models;

namespace LedgerX.Services;

public sealed record WarningEvaluation(bool CanEvaluate, bool Triggered, decimal? Value, string Explanation, IReadOnlyList<Guid> Evidence);

public sealed class WarningEngine(MetricEngine metrics)
{
    public WarningEvaluation Preview(WarningRule rule, LedgerState state, DateTime? anchor = null)
    {
        var conditions=rule.Conditions.Count>0?rule.Conditions:[new WarningCondition{MetricId=rule.MetricId,Operator=rule.Operator,Threshold=rule.Threshold,ComparisonMode=rule.ComparisonMode,ComparisonWindow=rule.ComparisonWindow}];
        var currentAnchor=(anchor??DateTime.Today).Date;var evaluations=new List<WarningEvaluation>();
        for(var index=0;index<Math.Max(1,rule.ConsecutivePeriods);index++)
        {
            var evaluationAnchor=rule.Period switch{"day"=>currentAnchor.AddDays(-index),"week"=>currentAnchor.AddDays(-7*index),"year"=>currentAnchor.AddYears(-index),_=>currentAnchor.AddMonths(-index)};
            evaluations.AddRange(conditions.Select(c=>EvaluateCondition(c,rule.Period,state,evaluationAnchor)));
        }
        if(evaluations.Any(x=>!x.CanEvaluate))return new(false,false,evaluations.FirstOrDefault()?.Value,string.Join("；",evaluations.Select(x=>x.Explanation).Distinct()),evaluations.SelectMany(x=>x.Evidence).Distinct().ToList());
        return new(true,evaluations.All(x=>x.Triggered),evaluations.FirstOrDefault()?.Value,string.Join("；",evaluations.Select(x=>x.Explanation).Distinct()),evaluations.SelectMany(x=>x.Evidence).Distinct().ToList());
    }
    public void EvaluateAll(LedgerState state, DateTime? nowValue = null)
    {
        var now = nowValue ?? DateTime.Now;
        foreach (var rule in state.WarningRules)
        {
            var existing = state.WarningEvents.Where(x => x.RuleId == rule.Id && x.Status is "active" or "read" or "snoozed").OrderByDescending(x => x.TriggeredAt).FirstOrDefault();
            if (!rule.Enabled || rule.EffectiveFrom > now || rule.EffectiveTo < now)
            {
                Resolve(existing, now);
                continue;
            }
            var evaluation = Preview(rule, state, now.Date);
            if (!evaluation.CanEvaluate) { Resolve(existing, now); continue; }
            if (evaluation.Triggered)
            {
                if (existing is null)
                {
                    var latest = state.WarningEvents.Where(x => x.RuleId == rule.Id).OrderByDescending(x => x.TriggeredAt).FirstOrDefault();
                    if (latest is not null && now - latest.TriggeredAt < TimeSpan.FromHours(Math.Max(0, rule.CooldownHours))) continue;
                    var range = MetricEngine.Range(rule.Period, now.Date);
                    state.WarningEvents.Add(new WarningEvent { RuleId = rule.Id, MeasuredValue = evaluation.Value, Threshold = rule.Threshold, PeriodStart = range.Start, PeriodEnd = range.End, TriggeredAt = now, LastEvaluatedAt = now, EvidenceRecordIds = evaluation.Evidence.ToList(), Explanation = evaluation.Explanation });
                }
                else
                {
                    existing.MeasuredValue = evaluation.Value; existing.LastEvaluatedAt = now; existing.EvidenceRecordIds = evaluation.Evidence.ToList(); existing.Explanation = evaluation.Explanation;
                    if (existing.Status == "snoozed" && existing.SnoozedUntil <= now) existing.Status = "active";
                }
            }
            else Resolve(existing, now);
        }
    }

    private WarningEvaluation EvaluateCondition(WarningCondition condition, string period, LedgerState state, DateTime anchor)
    {
        var currentRange = MetricEngine.Range(period, anchor);
        var current = metrics.Calculate(state.Records, currentRange, state.CustomMetrics, safetyTarget: state.Settings.SafetyBufferTarget).GetValueOrDefault(condition.MetricId);
        if (current?.Value is null) return new(false, false, null, current?.Status == "no-burn" ? "暂无消耗，暂不触发" : "数据不足，暂无法评估", []);
        decimal compareValue = condition.Threshold;
        var measured = current.Value.Value;
        if (condition.ComparisonMode == "consecutivePositive") return new(true, measured > 0, current.Value, $"{condition.MetricId} 为正（{measured:N2}）", current.Evidence);
        if (condition.ComparisonMode == "consecutiveNegative") return new(true, measured < 0, current.Value, $"{condition.MetricId} 为负（{measured:N2}）", current.Evidence);
        if (condition.ComparisonMode is "previousChangePercent" or "averageChangePercent")
        {
            var samples = new List<decimal>();
            var count = condition.ComparisonMode == "previousChangePercent" ? 1 : Math.Max(1, condition.ComparisonWindow);
            for (var i = 1; i <= count; i++)
            {
                var previousAnchor = period switch { "day" => anchor.AddDays(-i), "week" => anchor.AddDays(-7 * i), "year" => anchor.AddYears(-i), _ => anchor.AddMonths(-i) };
                var previous = metrics.Calculate(state.Records, MetricEngine.Range(period, previousAnchor), state.CustomMetrics, safetyTarget: state.Settings.SafetyBufferTarget).GetValueOrDefault(condition.MetricId)?.Value;
                if (previous is not null) samples.Add(previous.Value);
            }
            if (samples.Count < count || samples.Average() == 0) return new(false, false, measured, "历史样本不足，暂无法评估", current.Evidence);
            var baseline = condition.ComparisonMode == "previousChangePercent" ? samples[0] : samples.Average();
            measured = (measured - baseline) / Math.Abs(baseline) * 100m;
        }
        var triggered = condition.Operator switch { ">" => measured > compareValue, ">=" => measured >= compareValue, "=" => measured == compareValue, "<=" => measured <= compareValue, _ => measured < compareValue };
        return new(true, triggered, current.Value, $"{condition.MetricId} 当前比较值 {measured:N2} {condition.Operator} {compareValue:N2}", current.Evidence);
    }

    private static void Resolve(WarningEvent? existing, DateTime now)
    {
        if (existing is null) return;
        existing.Status = "resolved"; existing.ResolvedAt = now; existing.LastEvaluatedAt = now;
    }
}