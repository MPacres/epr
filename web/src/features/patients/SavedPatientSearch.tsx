import * as Dialog from '@radix-ui/react-dialog'
import { useId, useRef, useState, type KeyboardEvent } from 'react'
import { useQueries, useQuery } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'
import { ArrowDown, ArrowUp, Building2, CornerDownLeft, Search, X } from 'lucide-react'
import { clinicalApi, patientPath, type Scope } from '../encounters/services/encounter-api'
import { patientApi, patientKeys } from './patient-api'
import '../dashboard/patient-spotlight.css'
export function SavedPatientSearch({onSelect}:{onSelect:()=>void}) {
  const navigate=useNavigate(),[search,setSearch]=useState(''),[active,setActive]=useState(0)
  const input=useRef<HTMLInputElement>(null),list=useRef<HTMLDivElement>(null),opener=useRef(document.activeElement as HTMLElement|null),listId=useId()
  const scopes=useQuery({queryKey:['clinical','scopes'],queryFn:({signal})=>clinicalApi.scopes(signal),retry:false})
  const practices=(scopes.data??[]).filter((s,index,all)=>s.patientCapabilities?.includes('DIRECTORY_READ')&&all.findIndex(other=>other.practiceId===s.practiceId&&other.patientCapabilities?.includes('DIRECTORY_READ'))===index)
  const queries=useQueries({queries:practices.map(scope=>({queryKey:[...patientKeys(scope),'global-search',search],queryFn:({signal}:{signal:AbortSignal})=>patientApi.directory(scope,{search,identity:'all',sex:'all',sort:'name',page:0,recent:false},signal),enabled:!!search.trim(),retry:false}))})
  const results=queries.flatMap((query,index)=>query.error?[]:(query.data?.items??[]).map(item=>({...item,scope:practices[index]})))
  const index=Math.min(active,Math.max(0,results.length-1)),selected=results[index]
  const loading=scopes.isPending||(!!search.trim()&&queries.some(q=>q.isPending)),failed=!!scopes.error||queries.some(q=>q.isError)
  const optionId=(n:number)=>`${listId}-${n}`
  function open(scope:Scope,id:string){onSelect();navigate(patientPath(scope,id))}
  function update(value:string){setSearch(value);setActive(0);list.current?.scrollTo({top:0})}
  function keyboard(event:KeyboardEvent<HTMLInputElement>){if(event.nativeEvent.isComposing)return;if(event.key==='Enter'&&selected){event.preventDefault();open(selected.scope,selected.patient.patientId)}else if(['ArrowDown','ArrowUp'].includes(event.key)&&results.length){event.preventDefault();const next=(index+(event.key==='ArrowDown'?1:-1)+results.length)%results.length;setActive(next);document.getElementById(optionId(next))?.scrollIntoView({block:'nearest'})}}
  return <Dialog.Root open onOpenChange={value=>{if(!value)onSelect()}}><Dialog.Portal><Dialog.Overlay className="spotlight-overlay"/><Dialog.Content className="patient-spotlight" onOpenAutoFocus={event=>{event.preventDefault();input.current?.focus()}} onCloseAutoFocus={event=>{event.preventDefault();if(opener.current?.isConnected)opener.current.focus()}}>
    <Dialog.Title className="sr-only">Search patients</Dialog.Title><Dialog.Description className="sr-only">Search saved synthetic patients across your authorized Practices. Use arrow keys and Enter to open a chart.</Dialog.Description>
    <div className="spotlight-searchbar"><Search aria-hidden="true"/><input ref={input} role="combobox" aria-label="Search patients across your clinics" aria-autocomplete="list" aria-expanded={!!search.trim()} aria-controls={listId} aria-activedescendant={selected?optionId(index):undefined} placeholder="Search patients…" value={search} onChange={event=>update(event.target.value)} onKeyDown={keyboard} autoComplete="off" spellCheck={false}/>{search?<button className="spotlight-clear" aria-label="Clear patient search" onClick={()=>{update('');input.current?.focus()}}><X aria-hidden="true"/></button>:null}<Dialog.Close className="spotlight-close" aria-label="Close patient search"><span className="spotlight-escape">esc</span><span className="spotlight-close-label">Cancel</span></Dialog.Close></div>
    <div className="spotlight-scope"><span><Building2 aria-hidden="true"/>Authorized Practices</span><span>Saved synthetic records</span></div>
    <div className="spotlight-results-area"><p className="spotlight-results-label" role="status">{loading?'Searching authorized records…':failed?'Some authorized searches are unavailable':!search.trim()?'Patient search':`${results.length} matching patients`}</p>
      <div ref={list} className="spotlight-results" id={listId} role="listbox" aria-label="Matching patients" hidden={!results.length}>{results.map(({patient,scope},n)=><div role="option" aria-selected={n===index} id={optionId(n)} key={`${scope.practiceId}/${patient.patientId}`} className="spotlight-result" onPointerMove={event=>{if(event.pointerType==='mouse')setActive(n)}} onMouseDown={event=>event.preventDefault()} onClick={()=>open(scope,patient.patientId)}><span className="avatar blue" aria-hidden="true">{patient.displayName.split(' ').slice(0,2).map(s=>s[0]).join('')}</span><span className="spotlight-patient"><strong>{patient.displayName}</strong><small>{patient.recordNumber}<span>·</span>{patient.birthAccuracy==='estimated'?'Estimated ':''}{patient.birthDate??'Birth date unknown'}<span>·</span>{scope.practiceName} · {scope.siteName}</small></span><CornerDownLeft aria-hidden="true"/></div>)}</div>
      {!results.length&&!loading?<div className="spotlight-empty"><Search aria-hidden="true"/><h3>{failed?'Search unavailable':search.trim()?'No matching patients':'Find the right patient'}</h3><p>{failed?'Retry by changing the search or open My Patients.':!practices.length?'No authorized saved patient directories are available.':'Search by name or patient ID. Each result shows its Practice and location.'}</p></div>:null}
      {queries.some(q=>(q.data?.total??0)>20)?<p className="spotlight-results-label">More matches exist. Refine the search or use My Patients.</p>:null}
    </div><footer className="spotlight-footer"><span><kbd><ArrowUp aria-hidden="true"/></kbd><kbd><ArrowDown aria-hidden="true"/></kbd>to navigate</span><span><kbd><CornerDownLeft aria-hidden="true"/></kbd>to open</span><span className="spotlight-footer-hint">Chart review does not start care</span></footer>
  </Dialog.Content></Dialog.Portal></Dialog.Root>
}
