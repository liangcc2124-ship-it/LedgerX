import type { ReactNode } from 'react';
import { Icon } from '../icons';
export function Modal({title,children,onClose,wide=false}:{title:string;children:ReactNode;onClose:()=>void;wide?:boolean}){
 return <div className="modal-backdrop" onMouseDown={e=>e.target===e.currentTarget&&onClose()}><section className={`modal ${wide?'wide':''}`} role="dialog" aria-modal="true"><header><h2>{title}</h2><button className="icon-button" aria-label="关闭" onClick={onClose}><Icon name="close"/></button></header>{children}</section></div>
}
