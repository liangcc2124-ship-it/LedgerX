import type { LedgerRecord, Metric, MetricDetail, RecordDraft, Snapshot, WarningRule } from './types';

declare global { interface Window { chrome?: { webview?: { postMessage(message: unknown): void; addEventListener(type: 'message', listener: (event: MessageEvent) => void): void } } } }

const builtIns: Metric[] = [
  ['cash','现金储备','随时可用资金',false], ['netflow','净现金流','累计流入 − 流出',false],
  ['assets','总资产','现金 + 固定资产',false], ['chain','资金链','当前可支撑月数',false],
  ['fixedcost','固定成本','周期性、刚性成本',false], ['variablecost','可变成本','随使用变化的成本',false],
  ['fixedassets','固定资产','耐用品账面价值',false], ['payables','应付账款','尚未支付的义务',false],
  ['runway','Runway','按当前消耗可维持',false]
].map(([id,name,subtitle,canRecord]) => ({ id, name, subtitle, canRecord, displayValue: '¥ 0.00', rawValue: 0, isCustom:false, isHidden:false, isEnabled:true, displayFormat:'Currency' } as Metric));

let mock: Snapshot = {
  metrics: builtIns, records: [], deletedRecords: [], hideAllAmounts:false, themeName:'暖铜', dataFile:'浏览器演示模式（正式版保存至本机）',
  analysis:{ empty:true, income:0, fixedCost:0, variableCost:0, dependency:0, fixedCostRatio:0, verdict:'还没有记录。先录入一笔收入或成本，分析会立即出现。' }, warningRules:[], warningEvents:[], settings:{notificationsEnabled:false,autoBackupEnabled:true,autoBackupIntervalDays:7,autoBackupRetentionCount:10,lastSettingsSection:'general',currencySymbol:'¥',safetyBufferTarget:3000}, recovery:{required:false,availableSnapshots:[]}, schemaVersion:2,recordCount:0,deletedRecordCount:0,customMetricCount:0,warningRuleCount:0
};

const labels:Record<string,string>={Income:'现金流入',FixedCost:'固定成本',VariableCost:'可变成本',FixedAssetPurchase:'固定资产购置',PayableCreated:'新增应付账款',PayablePayment:'支付应付账款',CustomIncrease:'指标增加',CustomDecrease:'指标减少'};
const positiveTypes = new Set(['Income','CustomIncrease']);

function amountText(amount:number, type:string, hidden:boolean) {
  if (hidden) return '••••••';
  const sign = positiveTypes.has(type) ? '+ ' : '− ';
  const prefix = type === 'CustomIncrease' || type === 'CustomDecrease' ? '' : '¥ ';
  return sign + prefix + amount.toFixed(2);
}
function format(metric:Metric, value:number|null) {
  if (value === null) return '—';
  if (mock.hideAllAmounts || metric.isHidden) return '••••••';
  if (metric.id === 'chain') return value === 999 ? '暂无消耗' : value < 1 ? '偏紧' : value < 3 ? '需关注' : '稳健';
  if (metric.id === 'runway') return value === 999 ? '暂无消耗' : value.toFixed(1) + ' 个月';
  return metric.displayFormat === 'Percent' ? value.toFixed(2) + '%' : metric.displayFormat === 'Number' ? value.toFixed(2) : '¥ ' + value.toFixed(2);
}
function activeRecords() { return mock.records; }
function sum(type:string) { return activeRecords().filter(r=>r.type===type).reduce((n,r)=>n+r.amount,0); }
function values():Record<string,number> {
  const income=sum('Income'), fixed=sum('FixedCost'), variable=sum('VariableCost'), purchases=sum('FixedAssetPurchase');
  const payments=sum('PayablePayment'), created=sum('PayableCreated'), cash=income-fixed-variable-purchases-payments;
  const months=Math.max(1,new Set(activeRecords().map(r=>r.date.slice(0,7))).size);
  const burn=(fixed+variable+payments)/months, runway=burn>0?Math.max(0,cash)/burn:999;
  return {cash,netflow:income-fixed-variable-purchases-payments,assets:cash+purchases,chain:runway,fixedcost:fixed,variablecost:variable,fixedassets:purchases,payables:Math.max(0,created-payments),runway};
}
function evaluateWarnings() {
 const rules=mock.warningRules||[]; const vals=values();
 mock.warningEvents=rules.filter(r=>r.enabled).map(r=>{const value=vals[r.metricId]??0; const triggered=r.operator==='>'?value>r.threshold:r.operator==='>='?value>=r.threshold:r.operator==='<'?value<r.threshold:value<=r.threshold; const now=new Date().toISOString(); return {id:'event-'+r.id,ruleId:r.id,status:triggered?'active':'resolved',measuredValue:value,threshold:r.threshold,periodStart:now.slice(0,10),periodEnd:now.slice(0,10),triggeredAt:now,lastEvaluatedAt:now,evidenceRecordIds:[],explanation:r.metricId+' '+r.operator+' '+r.threshold};});
}function calculate() {
  const vals=values();
  mock.metrics=mock.metrics.map(m=>{
    const value=m.isCustom?activeRecords().filter(r=>r.customMetricId===m.id).reduce((n,r)=>n+(r.type==='CustomDecrease'?-r.amount:r.amount),0):vals[m.id];
    return {...m,rawValue:value,displayValue:format(m,value)};
  });
  mock.records=mock.records.map(r=>({...r,displayAmount:amountText(r.amount,r.type,mock.hideAllAmounts)}));
  const income=sum('Income'), fixed=sum('FixedCost'), variable=sum('VariableCost');
  const own=activeRecords().filter(r=>r.type==='Income' && r.category!=='父母支持').reduce((n,r)=>n+r.amount,0);
  const total=fixed+variable;
  const runway=vals.runway;
  evaluateWarnings();
  mock.analysis={empty:!activeRecords().length,income,fixedCost:fixed,variableCost:variable,dependency:income?(activeRecords().filter(r=>r.type==='Income'&&r.incomeSource==='父母支持').reduce((a,r)=>a+r.amount,0)/income*100):null,fixedCostRatio:total?(fixed/total*100):0,verdict:!activeRecords().length?'还没有记录。先录入一笔收入或成本，分析会立即出现。':runway<1?'资金链偏紧，建议先保留现金并检查可削减成本。':runway<3?'目前可维持时间有限，优先建立安全垫。':'资金链暂时稳健，可以继续观察成本结构。'};
}
function recordContribution(metric:Metric, r:LedgerRecord):MetricDetail['contributions'][number]|null {
  const sign = positiveTypes.has(r.type) ? 'positive' : r.type === 'FixedAssetPurchase' && metric.id === 'assets' ? 'neutral' : 'negative';
  const relevant = metric.isCustom ? r.customMetricId===metric.id : (
    (metric.id==='cash' || metric.id==='netflow') && ['Income','FixedCost','VariableCost','FixedAssetPurchase','PayablePayment'].includes(r.type) ||
    metric.id==='fixedcost' && r.type==='FixedCost' || metric.id==='variablecost' && r.type==='VariableCost' ||
    metric.id==='fixedassets' && r.type==='FixedAssetPurchase' || metric.id==='payables' && ['PayableCreated','PayablePayment'].includes(r.type) ||
    metric.id==='assets' && ['Income','FixedCost','VariableCost','PayablePayment','FixedAssetPurchase'].includes(r.type)
  );
  if (!relevant) return null;
  const amount = metric.isCustom && r.type==='CustomDecrease' ? -r.amount : sign==='negative' ? -r.amount : r.amount;
  const explanation = metric.id==='assets' && r.type==='FixedAssetPurchase' ? '现金减少、固定资产增加，总资产净影响为 0' : '该记录参与 ' + metric.name + ' 的计算';
  return {recordId:r.id,date:r.date,label:r.typeLabel + (r.category ? ' · ' + r.category : ''),amount,displayAmount:amountText(Math.abs(amount),amount>=0?'Income':'FixedCost',mock.hideAllAmounts),direction:sign,explanation};
}
function detail(metricId:string):MetricDetail {
  calculate();
  const metric=mock.metrics.find(m=>m.id===metricId) || mock.metrics[0];
  const vals=values(), value=metric.rawValue ?? null;
  const contributions=activeRecords().map(r=>recordContribution(metric,r)).filter(Boolean) as MetricDetail['contributions'];
  const days=[...new Set(activeRecords().map(r=>r.date))].sort().slice(-14);
  const trend=(days.length?days:['—']).map(d=>({label:d.slice(5),value:days.length?activeRecords().filter(r=>r.date===d).reduce((n,r)=>n+(positiveTypes.has(r.type)?r.amount:-r.amount),0):0}));
  const definitions:Record<string,string>={cash:'可立即使用的资金余额',netflow:'统计期间现金流入减去现金流出',assets:'现金与固定资产账面价值之和',fixedcost:'已发生的固定成本累计值',variablecost:'已发生的可变成本累计值',fixedassets:'固定资产购置金额累计值',payables:'新增应付账款减去已支付账款',chain:'现金储备按当前消耗可支撑的月数',runway:'现金储备除以平均月度消耗'};
  const formulas:Record<string,string>={cash:'流入 − 固定成本 − 可变成本 − 固定资产购置 − 应付账款支付',netflow:'流入 − 流出',assets:'现金 + 固定资产',fixedcost:'Σ 固定成本',variablecost:'Σ 可变成本',fixedassets:'Σ 固定资产购置',payables:'Σ 新增应付账款 − Σ 支付应付账款',chain:'现金储备 ÷ Burn Rate',runway:'现金储备 ÷ Burn Rate'};
  return {metric,periodLabel:'全部记录',value,comparisonValue:null,definition:definitions[metric.id] || '用户手动维护的累计指标',formula:formulas[metric.id] || 'Σ 增加 − Σ 减少',dataStatus:activeRecords().length?'ready':'empty',trend,components:metric.id==='runway'||metric.id==='chain'?[{label:'现金储备',value:vals.cash,sourceMetricId:'cash'},{label:'Burn Rate',value:(vals.fixedcost+vals.variablecost)/Math.max(1,new Set(activeRecords().map(r=>r.date.slice(0,7))).size),sourceMetricId:'runway'}]:undefined,contributions};
}
function report(kind:string) {
 calculate(); const v=values(), records=activeRecords(), outTypes=['FixedCost','VariableCost','FixedAssetPurchase','PayablePayment'];
 const grouped=Array.from(new Set(records.map(r=>r.date))).sort().slice(-31).map(date=>{const day=records.filter(r=>r.date===date),income=day.filter(r=>r.type==='Income').reduce((n,r)=>n+r.amount,0),outflow=day.filter(r=>outTypes.includes(r.type)).reduce((n,r)=>n+r.amount,0);return {label:date.slice(5),income,outflow,net:income-outflow,fixedCost:0,variableCost:0,assets:v.assets,payables:v.payables,recordIds:day.map(r=>r.id)}});
 const income=sum('Income'),outflow=sum('FixedCost')+sum('VariableCost')+sum('FixedAssetPurchase')+sum('PayablePayment');
 const top=(rows:LedgerRecord[],key:(r:LedgerRecord)=>string)=>{const groups=new Map<string,LedgerRecord[]>();for(const row of rows){const label=key(row);groups.set(label,[...(groups.get(label)||[]),row])}return Array.from(groups.entries()).map(([label,list])=>({label,value:list.reduce((n,r)=>n+r.amount,0),recordIds:list.map(r=>r.id)})).sort((a,b)=>b.value-a.value).slice(0,5)};
 const now=new Date().toISOString(); return {kind,periodLabel:kind==='day'?'日报 · 今日':kind==='week'?'周报 · 本周':kind==='month'?'月报 · 本月':'年报 · 本年',generatedAt:now.slice(0,16).replace('T',' '),unit:'人民币元',dataStatus:records.length?'ready':'empty',metrics:{inflow:income,outflow,netflow:income-outflow,cash:v.cash,fixedcost:v.fixedcost,variablecost:v.variablecost,payables:v.payables,assets:v.assets,runway:v.runway,dependency:mock.analysis.dependency},metricStatus:{cash:'ready',inflow:'ready',outflow:'ready',netflow:'ready',fixedcost:'ready',variablecost:'ready',payables:'ready',assets:'ready',runway:v.runway===999?'no-burn':'ready',dependency:income?'ready':'not-computable'},comparison:{inflow:0,outflow:0,netflow:0},series:grouped,costCategories:top(records.filter(r=>r.type==='FixedCost'||r.type==='VariableCost'),r=>r.category||'未分类'),incomeSources:top(records.filter(r=>r.type==='Income'),r=>r.incomeSource||'未知'),recordIds:records.map(r=>r.id),summary:records.length?(income-outflow>=0?'本周期净现金流为正。':'本周期净现金流为负。'):'本周期暂无记录。',upcomingPayables:0,openingCash:0,closingCash:v.cash,largestRecordId:null};
}
const pending = new Map<string, { resolve:(data:unknown)=>void; reject:(error:Error)=>void }>();
if (window.chrome?.webview) window.chrome.webview.addEventListener('message', event => {
  const response = typeof event.data === 'string' ? JSON.parse(event.data) : event.data;
  const waiter=pending.get(response.id); if(!waiter)return; pending.delete(response.id);
  response.ok ? waiter.resolve(response.data) : waiter.reject(new Error(response.error||'操作失败'));
});

async function mockInvoke(command:string,payload:any):Promise<any> {
  if(command==='getState'||command==='getDashboard') { calculate(); mock.selectedPeriod=payload.period||mock.selectedPeriod||'all'; return structuredClone(mock); }
  if(command==='getMetricDetail') return structuredClone(detail(payload.id));
  if(command==='getReport') return structuredClone(report(payload.kind||'month'));
  if(command==='getSettings') return {themeName:mock.themeName,customThemeCss:mock.customThemeCss||'',dataFile:mock.dataFile,notificationEnabled:false};
  if(command==='getDiagnostics') return {status:'正常',logEntries:[]};
  if(command==='previewWarningRule') { const rule=payload as WarningRule; const value=values()[rule.metricId]??0; const triggered=rule.operator==='<'?value<rule.threshold:rule.operator==='<='?value<=rule.threshold:rule.operator==='>'?value>rule.threshold:rule.operator==='>='?value>=rule.threshold:value===rule.threshold; return {canEvaluate:true,triggered,value,explanation:rule.metricId+' '+rule.operator+' '+rule.threshold}; }
  if(command==='saveWarningRule') { const rule=payload as WarningRule; const saved={...rule,id:rule.id||'rule-'+crypto.randomUUID()}; mock.warningRules=rule.id?mock.warningRules.map(r=>r.id===rule.id?saved:r):[...mock.warningRules,saved]; evaluateWarnings(); return structuredClone(mock); }
  if(command==='toggleWarningRule') mock.warningRules=mock.warningRules.map(r=>r.id===payload.id?{...r,enabled:payload.enabled}:r);
  if(command==='copyWarningRule') { const r=mock.warningRules.find(x=>x.id===payload.id);if(r)mock.warningRules.push({...r,id:'rule-'+crypto.randomUUID(),name:r.name+'（副本）'}); }
  if(command==='updateWarningEvent') mock.warningEvents=mock.warningEvents.map(e=>e.id===payload.id?{...e,status:payload.status}:e);
  if(command==='deleteWarningRule') { mock.warningRules=(mock.warningRules||[]).filter(r=>r.id!==payload.id); mock.warningEvents=(mock.warningEvents||[]).filter(e=>e.ruleId!==payload.id); return structuredClone(mock); }
  if(command==='clearLedger') { if(payload.confirmation!=='清空 LedgerX')throw new Error('确认文字不正确');mock.records=[];mock.deletedRecords=[];mock.metrics=mock.metrics.filter(m=>!m.isCustom);mock.warningRules=[];mock.warningEvents=[]; }
  if(command==='factoryReset') { if(payload.confirmation!=='恢复出厂')throw new Error('确认文字不正确');mock.records=[];mock.deletedRecords=[];mock.warningRules=[];mock.warningEvents=[];mock.themeName='暖铜'; }
  if(command==='saveSettings') mock.settings={...mock.settings,...payload};
  if(command==='bulkUpdateIncomeSource') mock.records=mock.records.map(r=>payload.ids.includes(r.id)?{...r,incomeSource:payload.incomeSource,isSelfGeneratedIncome:payload.isSelfGeneratedIncome}:r);
  if(command==='addRecord') { const d=payload as RecordDraft; const now=new Date().toISOString(); mock.records.unshift({...d,id:crypto.randomUUID(),typeLabel:labels[d.type],displayAmount:'',customMetricId:d.customMetricId,createdAt:now,updatedAt:now,deletedAt:null}); }
  if(command==='updateRecord') { const d=payload as RecordDraft&{id:string}; mock.records=mock.records.map(r=>r.id===d.id?{...r,...d,typeLabel:labels[d.type],updatedAt:new Date().toISOString()}:r); }
  if(command==='deleteRecord') { const record=mock.records.find(r=>r.id===payload.id); if(record){mock.records=mock.records.filter(r=>r.id!==payload.id);mock.deletedRecords.unshift({...record,deletedAt:new Date().toISOString()});} }
  if(command==='restoreRecord') { const record=mock.deletedRecords.find(r=>r.id===payload.id); if(record){mock.deletedRecords=mock.deletedRecords.filter(r=>r.id!==payload.id);mock.records.unshift({...record,deletedAt:null,updatedAt:new Date().toISOString()});} }
  if(command==='purgeRecord') mock.deletedRecords=mock.deletedRecords.filter(r=>r.id!==payload.id);
  if(command==='toggleAllPrivacy') mock.hideAllAmounts=!mock.hideAllAmounts;
  if(command==='toggleMetricPrivacy') mock.metrics=mock.metrics.map(m=>m.id===payload.id?{...m,isHidden:!m.isHidden}:m);
  if(command==='toggleMetricEnabled') mock.metrics=mock.metrics.map(m=>m.id===payload.id?{...m,isEnabled:payload.enabled}:m);
  if(command==='setMetricEnabled') mock.metrics=mock.metrics.map(m=>m.id===payload.id?{...m,isEnabled:payload.enabled}:m);
  if(command==='addCustomMetric') mock.metrics.push({id:'custom-'+crypto.randomUUID(),name:payload.name,subtitle:payload.subtitle||'自定义指标',displayFormat:payload.displayFormat,isCustom:true,canRecord:true,isEnabled:true,isHidden:false,displayValue:'¥ 0.00',rawValue:0});
  if(command==='deleteCustomMetric') { mock.metrics=mock.metrics.filter(m=>m.id!==payload.id); mock.records=mock.records.filter(r=>r.customMetricId!==payload.id); }
  if(command==='setTheme') mock.themeName=payload.name;
  calculate(); return structuredClone(mock);
}

export async function invoke<T=Snapshot>(command:string,payload:unknown={}):Promise<T> {
  if(!window.chrome?.webview) return mockInvoke(command,payload) as Promise<T>;
  const id=crypto.randomUUID();
  return new Promise<T>((resolve,reject)=>{ pending.set(id,{resolve:resolve as (data:unknown)=>void,reject}); window.chrome!.webview!.postMessage({id,command,payload}); });
}