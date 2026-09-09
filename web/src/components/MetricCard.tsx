import { Icon } from '../icons';
import type { Metric } from '../types';

export function MetricCard({metric,onRecord,onPrivacy}:{metric:Metric;onRecord:(m:Metric)=>void;onPrivacy:(id:string)=>void}){
 return <article className={`metric-card ${metric.isCustom?'custom':''}`} onClick={()=>metric.canRecord&&onRecord(metric)}>
  <div className="metric-top"><div className="metric-name"><span className="metric-icon"><Icon name={metric.id==='cash'?'wallet':'metrics'} size={17}/></span>{metric.name}</div><button className="icon-button faint" aria-label={`${metric.isHidden?'显示':'隐藏'}${metric.name}`} onClick={e=>{e.stopPropagation();onPrivacy(metric.id)}}><Icon name={metric.isHidden?'eyeOff':'eye'} size={17}/></button></div>
  <strong>{metric.displayValue}</strong><p>{metric.subtitle}</p>
 </article>
}
