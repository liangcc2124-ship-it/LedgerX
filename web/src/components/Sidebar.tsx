import type { Page } from '../types';
import { Icon } from '../icons';

const items: {id:Page; label:string}[]=[{id:'overview',label:'财务总览'},{id:'metrics',label:'指标中心'},{id:'records',label:'全部记录'},{id:'analysis',label:'财务分析'},{id:'warnings',label:'预警中心'}];
export function Sidebar({page,onNavigate}:{page:Page;onNavigate:(p:Page)=>void}){
 return <aside className="sidebar">
  <div className="brand"><img src="./ledgerx-icon.png"/><span>LedgerX</span></div>
  <nav>{items.map(i=><button key={i.id} className={page===i.id?'active':''} onClick={()=>onNavigate(i.id)}><Icon name={i.id}/><span>{i.label}</span></button>)}</nav>
  <button className={'settings-entry '+(page==='settings'?'active':'')} onClick={()=>onNavigate('settings')}><Icon name="data"/><span>设置</span></button>
 </aside>
}