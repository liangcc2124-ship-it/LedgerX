import { Icon } from '../icons';
import type { Metric } from '../types';

export function MetricCard({metric,onOpen,onRecord,onPrivacy}:{metric:Metric;onOpen:(m:Metric)=>void;onRecord:(m:Metric)=>void;onPrivacy:(id:string)=>void}){
 return <article className={'metric-card ' + (metric.isCustom?'custom':'')} onClick={()=>onOpen(metric)} tabIndex={0} onKeyDown={e=>{if(e.key==='Enter'||e.key===' ')onOpen(metric)}}>
  <div className="metric-top"><div className="metric-name"><span className="metric-icon"><Icon name={metric.id==='cash'?'wallet':'metrics'} size={17}/></span>{metric.name}</div><div className="metric-actions"><button className="icon-button faint" aria-label={(metric.isHidden?'显示':'隐藏')+metric.name} onClick={e=>{e.stopPropagation();onPrivacy(metric.id)}}><Icon name={metric.isHidden?'eyeOff':'eye'} size={17}/></button>{metric.canRecord&&<button className="icon-button faint" aria-label={'添加'+metric.name} onClick={e=>{e.stopPropagation();onRecord(metric)}}><Icon name="plus" size={16}/></button>}</div></div>
  <strong>{metric.displayValue}</strong><p>{metric.subtitle}</p>
 </article>
}