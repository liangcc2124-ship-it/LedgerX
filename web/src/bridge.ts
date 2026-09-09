import type { LedgerRecord, Metric, RecordDraft, Snapshot } from './types';

declare global { interface Window { chrome?: { webview?: { postMessage(message: unknown): void; addEventListener(type: 'message', listener: (event: MessageEvent) => void): void } } } }

const builtIns: Metric[] = [
  ['cash','现金储备','随时可用资金',true], ['netflow','净现金流','累计流入 − 流出',true],
  ['assets','总资产','现金 + 固定资产',true], ['chain','资金链','当前可支撑月数',false],
  ['fixedcost','固定成本','周期性、刚性成本',true], ['variablecost','可变成本','随使用变化的成本',true],
  ['fixedassets','固定资产','耐用品账面价值',true], ['payables','应付账款','尚未支付的义务',true],
  ['runway','Runway','按当前消耗可维持',false]
].map(([id,name,subtitle,canRecord]) => ({ id, name, subtitle, canRecord, displayValue: '¥ 0.00', rawValue: 0, isCustom:false, isHidden:false, isEnabled:true, displayFormat:'Currency' } as Metric));

let mock: Snapshot = {
  metrics: builtIns, records: [], hideAllAmounts:false, themeName:'暖铜', dataFile:'浏览器演示模式（正式版保存至本机）',
  analysis:{ empty:true, income:0, fixedCost:0, variableCost:0, dependency:0, fixedCostRatio:0, verdict:'还没有记录。先录入一笔收入或成本，分析会立即出现。' }
};

function format(metric: Metric, value: number) {
  if (mock.hideAllAmounts || metric.isHidden) return '••••••';
  if (metric.id === 'chain') return value === 999 ? '暂无消耗' : value < 1 ? '偏紧' : value < 3 ? '需关注' : '稳健';
  if (metric.id === 'runway') return value === 999 ? '—' : `${value.toFixed(1)} 个月`;
  return metric.displayFormat === 'Percent' ? `${value.toFixed(2)}%` : metric.displayFormat === 'Number' ? value.toFixed(2) : `¥ ${value.toFixed(2)}`;
}

function calculate() {
  const sum = (type:string) => mock.records.filter(r=>r.type===type).reduce((n,r)=>n+r.amount,0);
  const income=sum('Income'), fixed=sum('FixedCost'), variable=sum('VariableCost'), purchases=sum('FixedAssetPurchase');
  const payments=sum('PayablePayment'), payables=Math.max(0,sum('PayableCreated')-payments), cash=income-fixed-variable-purchases-payments;
  const months=Math.max(1,new Set(mock.records.map(r=>r.date.slice(0,7))).size), burn=(fixed+variable+payments)/months, runway=burn>0?Math.max(0,cash)/burn:999;
  const vals:Record<string,number>={cash,netflow:cash,assets:cash+purchases,chain:runway,fixedcost:fixed,variablecost:variable,fixedassets:purchases,payables,runway};
  mock.metrics=mock.metrics.map(m=>{ const custom=m.isCustom?mock.records.filter(r=>r.customMetricId===m.id).reduce((n,r)=>n+(r.type==='CustomDecrease'?-r.amount:r.amount),0):vals[m.id]; return {...m,rawValue:custom,displayValue:format(m,custom)}; });
  mock.records=mock.records.map(r=>({...r,displayAmount:mock.hideAllAmounts?'••••••':`${r.type==='Income'||r.type==='CustomIncrease'?'+':'−'} ${r.customMetricId?'':'¥ '}${r.amount.toFixed(2)}`}));
  const own=mock.records.filter(r=>r.type==='Income'&&!r.category.includes('父母')).reduce((n,r)=>n+r.amount,0), total=fixed+variable;
  mock.analysis={empty:!mock.records.length,income,fixedCost:fixed,variableCost:variable,dependency:income?((income-own)/income*100):0,fixedCostRatio:total?(fixed/total*100):0,verdict:!mock.records.length?'还没有记录。先录入一笔收入或成本，分析会立即出现。':runway<1?'资金链偏紧，建议先保留现金并检查可削减成本。':runway<3?'目前可维持时间有限，优先建立安全垫。':'资金链暂时稳健，可以继续观察成本结构。'};
}

const pending = new Map<string, { resolve:(data:unknown)=>void; reject:(error:Error)=>void }>();
if (window.chrome?.webview) window.chrome.webview.addEventListener('message', event => {
  const response = typeof event.data === 'string' ? JSON.parse(event.data) : event.data;
  const waiter=pending.get(response.id); if(!waiter)return; pending.delete(response.id);
  response.ok ? waiter.resolve(response.data) : waiter.reject(new Error(response.error||'操作失败'));
});

async function mockInvoke(command:string,payload:any):Promise<Snapshot> {
  if(command==='getState') return mock;
  if(command==='addRecord') { const d=payload as RecordDraft; const labels:Record<string,string>={Income:'现金流入',FixedCost:'固定成本',VariableCost:'可变成本',FixedAssetPurchase:'固定资产购置',PayableCreated:'新增应付账款',PayablePayment:'支付应付账款',CustomIncrease:'指标增加',CustomDecrease:'指标减少'}; mock.records.unshift({...d,id:crypto.randomUUID(),typeLabel:labels[d.type],displayAmount:'',customMetricId:d.customMetricId}); }
  if(command==='deleteRecord') mock.records=mock.records.filter(r=>r.id!==payload.id);
  if(command==='toggleAllPrivacy') mock.hideAllAmounts=!mock.hideAllAmounts;
  if(command==='toggleMetricPrivacy') mock.metrics=mock.metrics.map(m=>m.id===payload.id?{...m,isHidden:!m.isHidden}:m);
  if(command==='setMetricEnabled') mock.metrics=mock.metrics.map(m=>m.id===payload.id?{...m,isEnabled:payload.enabled}:m);
  if(command==='addCustomMetric') mock.metrics.push({id:`custom-${crypto.randomUUID()}`,name:payload.name,subtitle:payload.subtitle||'自定义指标',displayFormat:payload.displayFormat,isCustom:true,canRecord:true,isEnabled:true,isHidden:false,displayValue:'¥ 0.00',rawValue:0});
  if(command==='deleteCustomMetric') { mock.metrics=mock.metrics.filter(m=>m.id!==payload.id); mock.records=mock.records.filter(r=>r.customMetricId!==payload.id); }
  if(command==='setTheme') mock.themeName=payload.name;
  calculate(); return structuredClone(mock);
}

export async function invoke<T=Snapshot>(command:string,payload:unknown={}):Promise<T> {
  if(!window.chrome?.webview) return mockInvoke(command,payload) as Promise<T>;
  const id=crypto.randomUUID();
  return new Promise<T>((resolve,reject)=>{ pending.set(id,{resolve:resolve as (data:unknown)=>void,reject}); window.chrome!.webview!.postMessage({id,command,payload}); });
}
