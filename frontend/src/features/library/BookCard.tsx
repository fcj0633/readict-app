import {useState} from 'react';
import {BookOpen,Trash2} from 'lucide-react';
import type {Book} from './model';
import {bookStatusLabel,canOpen,canRetry} from './model';
import {coverPath,progressPercent} from './shelf-model';
export function BookCover({book}:{book:Book}){const[failed,setFailed]=useState(false),src=coverPath(book);return src&&!failed?<img src={src} alt={book.title+' 封面'} onError={()=>setFailed(true)}/>:<span className="cover-placeholder" role="img" aria-label={book.title+' 暂无封面'}><BookOpen size={32} strokeWidth={1.2}/><span>{book.title}</span></span>;}
export default function BookCard({book,onOpen,onDelete,onRetry}:{book:Book;onOpen:(b:Book)=>void;onDelete:(b:Book)=>void;onRetry:(b:Book)=>void}){
 const ready=canOpen(book.status),percent=progressPercent(book.progressRatio);
 return <article className="ql-book"><div className="ql-cover-stage"><button className="ql-cover" disabled={!ready} onClick={()=>onOpen(book)} aria-label={'阅读 '+book.title}><BookCover book={book}/><span className="ql-cover-action"><BookOpen size={20}/>翻开这本书</span></button><button className="ql-delete" aria-label={'删除 '+book.title} onClick={()=>onDelete(book)}><Trash2 size={15}/></button></div><button className="ql-book-title" disabled={!ready} onClick={()=>onOpen(book)} title={book.title}><h3>{book.title}</h3></button><p className="ql-book-author">{book.author||'作者未知'}</p><p className="ql-book-status">{ready?book.progressRatio===null?'未开始':percent===null?'阅读进度未知':'阅读中 · '+percent+'%':bookStatusLabel(book.status)}</p>{ready&&percent!==null&&<progress max={100} value={percent} aria-label={book.title+' 阅读进度'}/>} {canRetry(book.status)&&<button className="link-button" onClick={()=>onRetry(book)}>重新导入</button>}</article>;
}
