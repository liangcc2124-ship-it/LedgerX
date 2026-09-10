import { forwardRef } from 'react';

type Props={value:string;onChange:(value:string)=>void;label?:string;autoFocus?:boolean;required?:boolean;placeholder?:string;id?:string};
export const DecimalInput=forwardRef<HTMLInputElement,Props>(function DecimalInput({value,onChange,label,autoFocus,required,placeholder,id},ref){
 return <input ref={ref} id={id} aria-label={label} type="text" inputMode="decimal" autoComplete="off" autoFocus={autoFocus} required={required} value={value} placeholder={placeholder||'0.00'} onWheel={event=>event.preventDefault()} onKeyDown={event=>{if(event.key==='ArrowUp'||event.key==='ArrowDown')event.preventDefault()}} onChange={event=>{const next=event.target.value.replace(/，/g,'.');if(next===''||/^\d*(\.\d{0,8})?$/.test(next))onChange(next)}}/>;
});
