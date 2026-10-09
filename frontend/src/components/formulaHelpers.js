export const REFERENCE_KINDS = [
  { kind: 'METRIC', label: '指标' },
  { kind: 'CATEGORY_INCOME', label: '收入分类' },
  { kind: 'CATEGORY_EXPENSE', label: '支出分类' },
  { kind: 'ACCOUNT_BALANCE', label: '账户余额' },
  { kind: 'TIME', label: '时间变量' },
];

export const TIME_REFERENCES = [
  { key: 'period-days', label: '期间天数' },
  { key: 'elapsed-days', label: '已过天数' },
  { key: 'complete-months', label: '完整月数' },
];

export const FUNCTION_HINTS = [
  { name: '加', code: 'ADD', min: 2, max: 2, help: '两个值相加' },
  { name: '减', code: 'SUBTRACT', min: 2, max: 2, help: '计算差值' },
  { name: '乘', code: 'MULTIPLY', min: 2, max: 2, help: '计算乘积' },
  { name: '除', code: 'DIVIDE', min: 2, max: 2, help: '计算比值' },
  { name: '安全除法', code: 'SAFE_DIVIDE', min: 2, max: 2, help: '分母为零时返回不可计算' },
  { name: '最小值', code: 'MIN', min: 2, max: 32, help: '返回多个值中的最小值' },
  { name: '最大值', code: 'MAX', min: 2, max: 32, help: '返回多个值中的最大值' },
  { name: '平均值', code: 'AVG', min: 2, max: 32, help: '计算多个值的平均数' },
  { name: '取负', code: 'NEGATE', min: 1, max: 1, help: '将数值变为相反数' },
  { name: '绝对值', code: 'ABS', min: 1, max: 1, help: '取绝对值' },
  { name: '四舍五入', code: 'ROUND', min: 1, max: 2, help: '按精度舍入' },
  { name: '限制范围', code: 'CLAMP', min: 3, max: 3, help: '限制在上下限之间' },
];

export function cloneNode(node) {
  if (!node || typeof node !== 'object') return { kind: 'CONSTANT', value: '0' };
  if (node.kind === 'CONSTANT') return { kind: 'CONSTANT', value: String(node.value ?? '0') };
  if (node.kind === 'REF' || node.kind === 'REFERENCE') return { kind: 'REF', referenceKind: node.referenceKind, key: node.key };
  return { kind: String(node.kind).toUpperCase(), children: Array.isArray(node.children) ? node.children.map(cloneNode) : [] };
}

export function defaultNode(kind = 'CONSTANT') {
  if (kind === 'CONSTANT') return { kind, value: '0' };
  if (kind === 'REF') return { kind, referenceKind: 'METRIC', key: '' };
  const hint = FUNCTION_HINTS.find((item) => item.code === kind) || FUNCTION_HINTS[0];
  return { kind, children: Array.from({ length: hint.min }, () => ({ kind: 'CONSTANT', value: '0' })) };
}

export function nodeStats(node, depth = 1) {
  const children = Array.isArray(node?.children) ? node.children : [];
  return children.reduce((acc, child) => {
    const nested = nodeStats(child, depth + 1);
    return { count: acc.count + nested.count, depth: Math.max(acc.depth, nested.depth) };
  }, { count: 1, depth });
}

