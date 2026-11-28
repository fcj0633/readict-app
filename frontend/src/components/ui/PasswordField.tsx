import {useState} from 'react';
import {Eye,EyeOff} from 'lucide-react';
export function PasswordField({id,label,value,onChange,disabled=false,autoComplete='new-password'}:{id:string;label:string;value:string;onChange:(s:string)=>void;disabled?:boolean;autoComplete?:string}){
 const[visible,setVisible]=useState(false);return <div className="password-field"><label htmlFor={id}>{label}</label><div><input id={id} type={visible?'text':'password'} value={value} onChange={e=>onChange(e.target.value)} required disabled={disabled} autoComplete={autoComplete} maxLength={256}/><button type="button" aria-label={(visible?'隐藏':'显示')+label} onClick={()=>setVisible(v=>!v)}>{visible?<EyeOff size={17}/>:<Eye size={17}/>}</button></div></div>;
}
