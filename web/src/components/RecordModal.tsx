import { useMemo, useState } from 'react';
import type { LedgerRecord, Metric, RecordDraft } from '../types';
import { Modal } from './Modal';

const builtInTypes=[['Income','现金流入'],['FixedCost','固定成本'],['VariableCost','可变成本'],['FixedAssetPurchase','固定资产购置'],['PayableCreated','新增应付账款'],['PayablePayment','支付应付账款']];
const incomeSources=['父母支持','奖学金','兼职','实习或工资','投资收益','退款','其他'];
export function RecordModal({metrics,preset,record,onClose,onSave}:{metrics:Metric[];preset?:Metric|null;record?:LedgerRecord|null;onClose:()=>void;onSave:(draft:RecordDraft)=>Promise<void>}){
 const custom=preset?.isCustom?metrics.find(m=>m.id===preset.id):record?.customMetricId?metrics.find(m=>m.id===record.customMetricId):undefined;
 const initialType=record?.type||(custom?'CustomIncrease':preset?.id==='fixedcost'?'FixedCost':preset?.id==='variablecost'?'VariableCost':preset?.id==='fixedassets'?'FixedAssetPurchase':preset?.id==='payables'?'PayableCreated':'Income');
 const [form,setForm]=useState<RecordDraft>({date:record?.date||new Date().toISOString().slice(0,10),type:initialType,amount:record?.amount||0,category:record?.category||'',account:record?.account||'现金储备',note:record?.note||'',customMetricId:record?.customMetricId||custom?.id,incomeSource:record?.incomeSource||undefined,isSelfGeneratedIncome:record?.isSelfGeneratedIncome||false,isNonEssential:record?.isNonEssential||false}); const [saving,setSaving]=useState(false);
 const customMetrics=useMemo(()=>metrics.filter(m=>m.isCustom),[metrics]); const customMode=form.type.startsWith('Custom'); const incomeMode=form.type==='Income';
 const submit=async(e:React.FormEvent)=>{e.preventDefault();if(form.amount<=0)return;setSaving(true);try{await onSave({...form,customMetricId:customMode?(form.customMetricId||customMetrics[0]?.id):null,incomeSource:incomeMode?form.incomeSource:null,isSelfGeneratedIncome:incomeMode?form.isSelfGeneratedIncome:false});}finally{setSaving(false)}};
 return <Modal title={record?'编辑记录':custom?'记录 · '+custom.name:'新增记录'} onClose={onClose}><form onSubmit={submit} className="form-grid">
  <label>日期<input type="date" value={form.date} onChange={e=>setForm({...form,date:e.target.value})} required/></label>
  <label>记录类型<select value={form.type} onChange={e=>setForm({...form,type:e.target.value})}>{custom&&!record?<><option value="CustomIncrease">指标增加</option><option value="CustomDecrease">指标减少</option></>:<>{builtInTypes.map(([v,l])=><option key={v} value={v}>{l}</option>)}{customMetrics.length>0&&<><option value="CustomIncrease">自定义指标增加</option><option value="CustomDecrease">自定义指标减少</option></>}</>}</select></label>
  {customMode&&<label className="span-2">指标<select value={form.customMetricId||customMetrics[0]?.id||''} onChange={e=>setForm({...form,customMetricId:e.target.value})}>{customMetrics.map(m=><option key={m.id} value={m.id}>{m.name}</option>)}</select></label>}
  <label className="span-2">金额 / 数值<input type="number" min="0.01" step="0.01" value={form.amount||''} onChange={e=>setForm({...form,amount:Number(e.target.value)})} placeholder="0.00" autoFocus required/></label>
  <label>分类<input value={form.category} onChange={e=>setForm({...form,category:e.target.value})} placeholder="例如：吃饭、住宿"/></label>
  <label>账户<input value={form.account} onChange={e=>setForm({...form,account:e.target.value})} placeholder="现金储备"/></label>
  {incomeMode&&<><label>收入来源<select value={form.incomeSource||''} onChange={e=>setForm({...form,incomeSource:e.target.value})}><option value="">请选择</option>{incomeSources.map(x=><option key={x}>{x}</option>)}</select></label><label className="checkbox-label"><input type="checkbox" checked={!!form.isSelfGeneratedIncome} onChange={e=>setForm({...form,isSelfGeneratedIncome:e.target.checked})}/>属于自主收入</label></>}
  {(form.type==='FixedCost'||form.type==='VariableCost')&&<label className="checkbox-label span-2"><input type="checkbox" checked={!!form.isNonEssential} onChange={e=>setForm({...form,isNonEssential:e.target.checked})}/>非必要成本（可以砍掉但不影响基本生活）</label>}
  {record?<div className="edit-preview span-2" role="status"><b>修改预览</b><span>{record.typeLabel} ¥ {record.amount.toFixed(2)} → {builtInTypes.find(([v])=>v===form.type)?.[1]||'自定义指标'} ¥ {form.amount.toFixed(2)}</span><small>保存后将重新计算现金、资产、报告和预警；原创建时间保持不变。</small></div>:null}  <label className="span-2">备注<textarea value={form.note} onChange={e=>setForm({...form,note:e.target.value})} placeholder="可选"/></label>
  <div className="form-actions span-2"><button type="button" className="button ghost" onClick={onClose}>取消</button><button className="button primary" disabled={saving}>{saving?'保存中…':record?'保存修改':'保存记录'}</button></div>
 </form></Modal>
}