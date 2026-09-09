import { useMemo, useState } from 'react';
import type { Metric, RecordDraft } from '../types';
import { Modal } from './Modal';

const builtInTypes=[['Income','现金流入'],['FixedCost','固定成本'],['VariableCost','可变成本'],['FixedAssetPurchase','固定资产购置'],['PayableCreated','新增应付账款'],['PayablePayment','支付应付账款']];
export function RecordModal({metrics,preset,onClose,onSave}:{metrics:Metric[];preset?:Metric|null;onClose:()=>void;onSave:(draft:RecordDraft)=>Promise<void>}){
 const custom=preset?.isCustom?metrics.find(m=>m.id===preset.id):undefined;
 const initialType=custom?'CustomIncrease':preset?.id==='fixedcost'?'FixedCost':preset?.id==='variablecost'?'VariableCost':preset?.id==='fixedassets'?'FixedAssetPurchase':preset?.id==='payables'?'PayableCreated':'Income';
 const [form,setForm]=useState<RecordDraft>({date:new Date().toISOString().slice(0,10),type:initialType,amount:0,category:'',account:'现金储备',note:'',customMetricId:custom?.id}); const [saving,setSaving]=useState(false);
 const customMetrics=useMemo(()=>metrics.filter(m=>m.isCustom),[metrics]); const customMode=form.type.startsWith('Custom');
 const submit=async(e:React.FormEvent)=>{e.preventDefault();if(form.amount<=0)return;setSaving(true);try{await onSave({...form,customMetricId:customMode?(form.customMetricId||customMetrics[0]?.id):null});onClose()}finally{setSaving(false)}};
 return <Modal title={custom?`记录 · ${custom.name}`:'新增记录'} onClose={onClose}><form onSubmit={submit} className="form-grid">
  <label>日期<input type="date" value={form.date} onChange={e=>setForm({...form,date:e.target.value})} required/></label>
  <label>记录类型<select value={form.type} onChange={e=>setForm({...form,type:e.target.value})}>{custom?<><option value="CustomIncrease">指标增加</option><option value="CustomDecrease">指标减少</option></>:<>{builtInTypes.map(([v,l])=><option key={v} value={v}>{l}</option>)}{customMetrics.length>0&&<><option value="CustomIncrease">自定义指标增加</option><option value="CustomDecrease">自定义指标减少</option></>}</>}</select></label>
  {customMode&&<label className="span-2">指标<select value={form.customMetricId||customMetrics[0]?.id||''} onChange={e=>setForm({...form,customMetricId:e.target.value})}>{customMetrics.map(m=><option key={m.id} value={m.id}>{m.name}</option>)}</select></label>}
  <label className="span-2">金额 / 数值<input type="number" min="0.01" step="0.01" value={form.amount||''} onChange={e=>setForm({...form,amount:Number(e.target.value)})} placeholder="0.00" autoFocus required/></label>
  <label>分类<input value={form.category} onChange={e=>setForm({...form,category:e.target.value})} placeholder="例如：父母生活费、吃饭"/></label>
  <label>账户<input value={form.account} onChange={e=>setForm({...form,account:e.target.value})} placeholder="现金储备"/></label>
  <label className="span-2">备注<textarea value={form.note} onChange={e=>setForm({...form,note:e.target.value})} placeholder="可选"/></label>
  <div className="form-actions span-2"><button type="button" className="button ghost" onClick={onClose}>取消</button><button className="button primary" disabled={saving}>{saving?'保存中…':'保存记录'}</button></div>
 </form></Modal>
}
