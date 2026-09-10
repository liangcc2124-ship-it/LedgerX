using LedgerX.Models;

namespace LedgerX.Services;

public sealed class AccountEngine
{
    public IReadOnlyDictionary<Guid, decimal> Balances(LedgerState state, DateTime asOf)
    {
        var balances = state.FinancialAccounts.ToDictionary(x => x.Id, x => x.OpeningDate.Date <= asOf.Date ? x.OpeningBalance : 0m);
        foreach (var record in state.Records.Where(x => x.DeletedAt is null && CashDate(x) <= asOf.Date))
        {
            if (record.SettlementMode != SettlementMode.PaidFromAccount || record.FinancialAccountId is not Guid accountId)
                continue;
            if (!balances.ContainsKey(accountId)) continue;
            var account = state.FinancialAccounts.First(x => x.Id == accountId);
            balances[accountId] += SignedMovement(record, account);
        }
        return balances;
    }

    public decimal AvailableCash(LedgerState state, DateTime asOf)
    {
        var balances = Balances(state, asOf);
        return state.FinancialAccounts
            .Where(x => x.IncludeInAvailableCash && x.BalanceSide == BalanceSide.Asset && !x.IsArchived)
            .Sum(x => balances.GetValueOrDefault(x.Id));
    }

    public decimal Liabilities(LedgerState state, DateTime asOf)
    {
        var balances = Balances(state, asOf);
        var accountLiabilities = state.FinancialAccounts
            .Where(x => x.BalanceSide == BalanceSide.Liability)
            .Sum(x => Math.Max(0, balances.GetValueOrDefault(x.Id)));
        var ledgerPayables = state.Records
            .Where(x => x.DeletedAt is null && x.Date.Date <= asOf.Date)
            .Sum(x => x.Type == FinanceRecordType.PayableCreated ? x.Amount : x.Type == FinanceRecordType.PayablePayment ? -x.Amount : 0m);
        return Math.Max(0, accountLiabilities + ledgerPayables);
    }

    public static bool AffectsCash(FinanceRecord record) =>
        record.SettlementMode == SettlementMode.PaidFromAccount &&
        record.Type is not FinanceRecordType.PayableCreated and not FinanceRecordType.CustomIncrease and not FinanceRecordType.CustomDecrease;

    public static DateTime CashDate(FinanceRecord record) => (record.SettlementDate ?? record.Date).Date;

    public static decimal CashFlow(FinanceRecord record)
    {
        if (!AffectsCash(record)) return 0m;
        return record.Type == FinanceRecordType.Income ? record.Amount : -record.Amount;
    }

    private static decimal SignedMovement(FinanceRecord record, FinancialAccount account)
    {
        var flow = CashFlow(record);
        return account.BalanceSide == BalanceSide.Asset ? flow : -flow;
    }
}
