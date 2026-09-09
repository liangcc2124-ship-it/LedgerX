export type Page = 'overview' | 'metrics' | 'records' | 'analysis' | 'data';

export type Metric = {
  id: string; name: string; subtitle: string; displayValue: string;
  rawValue?: number | null; canRecord: boolean; isCustom: boolean;
  isHidden: boolean; isEnabled: boolean; displayFormat: 'Currency' | 'Number' | 'Percent';
};

export type LedgerRecord = {
  id: string; date: string; type: string; typeLabel: string; amount: number;
  displayAmount: string; category: string; account: string; note: string; customMetricId?: string | null;
};

export type Snapshot = {
  metrics: Metric[]; records: LedgerRecord[]; hideAllAmounts: boolean;
  themeName: string; customThemeCss?: string | null; dataFile: string;
  analysis: { empty: boolean; income: number; fixedCost: number; variableCost: number; dependency: number; fixedCostRatio: number; verdict: string };
};

export type RecordDraft = {
  date: string; type: string; amount: number; category: string; account: string;
  note: string; customMetricId?: string | null;
};
