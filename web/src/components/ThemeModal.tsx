import type { Snapshot } from '../types'; import { Modal } from './Modal';
const themes=[['暖铜','温润、克制，当前推荐'],['石墨','冷静的中性灰'],['深海蓝','更深、更专注']];
export function ThemeModal({state,onClose,onCommand}:{state:Snapshot;onClose:()=>void;onCommand:(c:string,p?:unknown)=>Promise<void>}){
 return <Modal title="外观与皮肤" onClose={onClose} wide><div className="theme-list">{themes.map(([name,desc])=><button key={name} className={state.themeName===name?'selected':''} onClick={()=>onCommand('setTheme',{name})}><span className={`swatch ${name}`}></span><span><b>{name}</b><small>{desc}</small></span>{state.themeName===name&&<em>使用中</em>}</button>)}</div><div className="custom-css"><div><b>自定义 CSS</b><p>只读取本地 CSS 变量，不执行脚本和远程内容。</p></div><button className="button ghost" onClick={()=>onCommand('importTheme')}>导入 CSS</button><button className="button ghost" onClick={()=>onCommand('exportTheme')}>导出当前皮肤</button></div></Modal>
}
