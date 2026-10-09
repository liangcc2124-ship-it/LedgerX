const withUnknown = (value, map) => Object.prototype.hasOwnProperty.call(map, value)
  ? map[value]
  : `未知（${value ?? '空'}）`;

export function accountKindLabel(value) {
  return withUnknown(value, { CASH: '现金', BANK: '银行', WALLET: '电子钱包', CREDIT: '信用卡', LOAN: '贷款', OTHER_ASSET: '其他资产', OTHER_LIABILITY: '其他负债' });
}

export function balanceSideLabel(value) {
  return withUnknown(value, { ASSET: '资产', LIABILITY: '负债' });
}

export function entityStatusLabel(value) {
  return withUnknown(value, { ACTIVE: '启用', INACTIVE: '未启用', ARCHIVED: '已归档' });
}

export function displayFormatLabel(value) {
  return withUnknown(value, { CURRENCY: '金额', PERCENT: '百分比', NUMBER: '数字', INTEGER: '整数' });
}

export function metricDataStatusLabel(value) {
  return withUnknown(value, { READY: '已计算', EMPTY: '暂无记录', FUTURE: '未来期间', HISTORICAL: '历史期间', CURRENT: '当前期间', NOT_COMPUTABLE: '暂不可计算', DEPENDENCY_UNAVAILABLE: '依赖不可用', UNAVAILABLE: '暂不可用' });
}

export function recordTypeLabel(value) {
  return withUnknown(value, { INCOME: '收入', FIXED_COST: '固定支出', VARIABLE_COST: '弹性支出' });
}
