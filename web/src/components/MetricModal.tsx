import { useState } from 'react'; import { Modal } from './Modal';
export function MetricModal({onClose,onSave}:{onClose:()=>void;onSave:(d:{name:string;subtitle:string;displayFormat:string})=>Promise<void>}){
 const [name,setName]=useState(''),[subtitle,setSubtitle]=useState(''),[displayFormat,setFormat]=useState('Currency'),[saving,setSaving]=useState(false);
 return <Modal title="新增指标" onClose={onClose}><form className="form-grid" onSubmit={async e=>{e.preventDefault();if(!name.trim())return;setSaving(true);try{await onSave({name:name.trim(),subtitle:subtitle.trim()||'自定义指标',displayFormat});onClose()}finally{setSaving(false)}}}>
  <label className="span-2">指标名称<input value={name} onChange={e=>setName(e.target.value)} placeholder="例如：安全垫" autoFocus required/></label>
  <label className="span-2">说明<input value={subtitle} onChange={e=>setSubtitle(e.target.value)} placeholder="例如：应对意外情况预留的钱"/></label>
  <label className="span-2">显示格式<select value={displayFormat} onChange={e=>setFormat(e.target.value)}><option value="Currency">金额 ¥</option><option value="Number">数值</option><option value="Percent">百分比 %</option></select></label>
  <div className="form-actions span-2"><button type="button" className="button ghost" onClick={onClose}>取消</button><button className="button primary" disabled={saving}>创建指标</button></div>
 </form></Modal>
}
