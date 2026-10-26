import { useEffect, useRef, useState, type FocusEvent } from 'react'
import { FormProvider, useForm, type FieldErrors } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useBlocker, useNavigate } from 'react-router-dom'
import { ArrowLeft, Building2, CircleAlert, Save, Search, ShieldCheck } from 'lucide-react'
import { Button } from '../../components/ui/button'
import { ConfirmationDialog } from '../../components/ui/confirmation-dialog'
import { createUuid } from '../../lib/create-uuid'
import { ClinicalRequestError, patientPath, type Scope } from '../encounters/services/encounter-api'
import type * as Wire from '../encounters/services/api.generated'
import { PatientFormSections } from './PatientFormSections'
import { FormField } from './PatientFormField'
import { changeValue, draftFields, emptyDraft, type FormSection, type PatientDraft } from './patient-form-model'
import { createPatientDraftSchema } from './patient-form-schema'
import { PatientIdentifierContext } from './patient-identifier-context'
import { detailDraft, editIdentifierModes, identifierChanges, newIdentifierModes, patientApi, patientKeys, registrationData, type DuplicateReview, type PatientDetail } from './patient-api'
import './patient-form.css'
export function SavedPatientForm({scope,patientId,starting}:{scope:Scope;patientId?:string;starting:boolean}) {
  const detail=useQuery({queryKey:[...patientKeys(scope),patientId],queryFn:({signal})=>patientApi.detail(scope,patientId!,signal),enabled:!!patientId,retry:false})
  if (patientId && detail.isPending) return <main id="main-content" className="dashboard"><p role="status">Loading saved patient details…</p></main>
  if (patientId && (detail.error || !detail.data?.canEdit)) return <main id="main-content" className="dashboard"><h1>Patient editing unavailable</h1><p role="alert">{detail.error?.message ?? 'Current demographic-edit permission is required.'}</p><Button onClick={()=>void detail.refetch()}>Retry access check</Button></main>
  return <SavedPatientEditor key={`${scope.practiceId}/${patientId??'new'}`} scope={scope} initial={detail.data} starting={starting}/>
}
function SavedPatientEditor({scope,initial,starting}:{scope:Scope;initial?:PatientDetail;starting:boolean}) {
  const navigate=useNavigate(),client=useQueryClient()
  const [baseline,setBaseline]=useState(initial)
  const [sections,setSections]=useState<FormSection[]>(initial?['address','philhealth']:['demographics','philhealth'])
  const [modes,setModes]=useState(initial?editIdentifierModes:newIdentifierModes)
  const [destination,setDestination]=useState(starting?'encounter':'chart')
  const [review,setReview]=useState<{result:DuplicateReview;fingerprint:string;confirmed:boolean}|null>(null)
  const [error,setError]=useState(''),[conflict,setConflict]=useState<PatientDetail|null>(null)
  const [pending,setPending]=useState<{key:string;body:Wire.PatientRegistration|Wire.PatientEdit}|null>(null)
  const patientId=useRef(initial?.patient.patientId??createUuid()),allowExit=useRef(false),busy=useRef(false),summary=useRef<HTMLDivElement>(null)
  const form=useForm<PatientDraft>({defaultValues:initial?detailDraft(initial):{...emptyDraft},resolver:zodResolver(createPatientDraftSchema(initial?.originalDisplayName??undefined)),shouldFocusError:false})
  const dirty=form.formState.isDirty || !!pending || JSON.stringify(modes)!==JSON.stringify(initial?editIdentifierModes:newIdentifierModes)
  const blocker=useBlocker(()=>!allowExit.current && dirty)
  useEffect(()=>{const warn=(event:BeforeUnloadEvent)=>{if(dirty&&!allowExit.current){event.preventDefault();event.returnValue=''}};window.addEventListener('beforeunload',warn);return()=>window.removeEventListener('beforeunload',warn)},[dirty])
  useEffect(()=>{document.title=`${initial?'Edit patient':'New patient'} · EPR`},[initial])
  const back=()=>navigate(initial?patientPath(scope,patientId.current):`/patients?scope=${encodeURIComponent(`${scope.practiceId}/${scope.siteId}`)}`)
  function showError(message:string){setError(message);requestAnimationFrame(()=>{summary.current?.focus();summary.current?.scrollIntoView({block:'center'})})}
  function invalid(errors:FieldErrors<PatientDraft>){setSections(current=>[...new Set([...current,...Object.keys(errors).map(k=>draftFields[k as keyof PatientDraft].section)])]);showError('Check the highlighted fields. Your entered details have been kept.')}
  async function save(values:PatientDraft){
    if(busy.current)return;busy.current=true;setError('')
    try{
      let command=pending
      if(!command){
        const data=registrationData(values),fingerprint=JSON.stringify(data)
        let checked=review
        if(!checked || checked.fingerprint!==fingerprint){
          const result=await patientApi.check(scope,{data,excludePatientId:initial?.patient.patientId})
          checked={result,fingerprint,confirmed:result.candidates.length===0};setReview(checked)
        }
        if(!checked.confirmed){showError('Review the possible matches below, then confirm this is a separate patient.');return}
        const identifiers=identifierChanges(values,modes)
        const body=initial?{expectedVersion:baseline!.patient.version,data,identifiers,duplicateReviewId:checked.result.reviewId,correctionReason:values.changeNote}:{patientId:patientId.current,data,identifiers,duplicateReviewId:checked.result.reviewId,syntheticOnly:true}
        command={key:createUuid(),body};setPending(command)
      }
      const saved=initial?await patientApi.edit(scope,patientId.current,command.body as Wire.PatientEdit,command.key):await patientApi.create(scope,command.body as Wire.PatientRegistration,command.key)
      allowExit.current=true;form.reset(detailDraft(saved));setPending(null)
      await client.invalidateQueries({queryKey:patientKeys(scope)})
      navigate(`${patientPath(scope,saved.patient.patientId)}${!initial&&destination==='encounter'?'?start=1':''}`,{replace:true})
    }catch(e){
      if(e instanceof ClinicalRequestError && e.status>=400 && e.status<500){
        setPending(null)
        if(e.code.startsWith('DUPLICATE_')){setReview(null);showError('The possible matches changed or the review expired. Save again to check the current records.');return}
        if(e.code==='PATIENT_VERSION_CONFLICT'){
          try {
            const latest=await patientApi.detail(scope,patientId.current);setConflict(latest)
            showError('Another editor saved changes. Compare the saved values with your draft below before applying a correction.')
          } catch(reloadError) {
            showError(reloadError instanceof Error ? `The current revision could not be loaded. ${reloadError.message} Your draft is preserved; retry Save changes to reload the comparison.` : 'The current revision could not be loaded. Your draft is preserved; retry Save changes.')
          }
          return
        }
        showError(e.code==='CORRECTION_REASON_REQUIRED'?'Enter a correction reason before saving.':`${e.message} (${e.code})`)
      }else showError('The save outcome is unconfirmed. Keep this page open and retry the original save. Your submitted details are locked until the server confirms the result.')
    }finally{busy.current=false}
  }
  function focusVisible(event:FocusEvent<HTMLElement>){const target=event.target;if(!(target instanceof HTMLElement)||!target.closest('.patient-form-sections,.patient-form-aside'))return;const bounds=target.getBoundingClientRect(),top=document.querySelector('.topbar')?.getBoundingClientRect().bottom??0,bottom=document.querySelector('.patient-save-bar')?.getBoundingClientRect().top??window.innerHeight;if(bounds.top<top+12||bounds.bottom>bottom-12)window.scrollBy({top:bounds.top-top-Math.max(12,(bottom-top-bounds.height)/2),behavior:'instant'})}
  const identifiers=initial?{masks:baseline!.identifiers,modes,change:(field:keyof typeof modes,mode:typeof modes[keyof typeof modes])=>setModes(value=>({...value,[field]:mode}))}:null
  return <main id="main-content" className="dashboard patient-form-workspace" tabIndex={-1} onFocusCapture={focusVisible}>
    <nav className="patient-breadcrumb" aria-label="Breadcrumb"><button onClick={back}><ArrowLeft aria-hidden="true"/>My Patients</button><span>/</span><span aria-current="page">{initial?'Edit patient':'New patient'}</span></nav>
    <div className="page-heading patient-form-heading"><div><h1>{initial?'Edit patient':'New patient'}</h1><p>{initial?'Correct saved patient details':'Register a patient'} at {scope.practiceName}.</p></div><span className="form-draft-status">{pending?'Save unconfirmed':dirty?'Unsaved changes':initial?'Saved record':'New record'}</span></div>
    <p className="form-demo-notice"><ShieldCheck aria-hidden="true"/>Demo only · Use fictional patient information. Registration does not start an encounter.</p>
    {baseline?<div className="form-patient-identity"><div><strong>{baseline.patient.displayName}</strong><span>{baseline.patient.recordNumber} · {baseline.data.birthAccuracy==='estimated'?'Estimated ':''}{baseline.patient.birthDate??'Birth date unknown'} · {scope.practiceName}</span>{baseline.originalDisplayName?<span>Original unsplit name: {baseline.originalDisplayName}. Leave name parts blank to keep it.</span>:null}</div></div>:null}
    <FormProvider {...form}><PatientIdentifierContext.Provider value={identifiers}><form noValidate onSubmit={event=>{if(pending){event.preventDefault();void save(form.getValues())}else void form.handleSubmit(save,invalid)(event)}}>
      {error?<div className="form-error-summary" ref={summary} tabIndex={-1} role="alert"><CircleAlert aria-hidden="true"/><div><h2>{pending?'Save needs confirmation':'Patient details not saved'}</h2><p>{error}</p></div></div>:null}
      {conflict?<section className="surface registration-conflict"><h2>Compare before saving</h2><p>Your draft is preserved. Saved revision: {conflict.patient.version}.</p><div className="registration-comparison"><table><thead><tr><th>Field</th><th>Currently saved</th><th>Your draft</th></tr></thead><tbody>{(Object.keys(draftFields) as (keyof PatientDraft)[]).filter(key=>!['pin','principalPin','otherIdValue','changeNote'].includes(key)&&detailDraft(conflict)[key]!==form.getValues(key)).map(key=><tr key={key}><th>{draftFields[key].label}</th><td>{changeValue(key,detailDraft(conflict)[key])}</td><td>{changeValue(key,form.getValues(key))}</td></tr>)}</tbody></table></div><p>Identifiers remain masked. “Keep existing value” keeps the latest saved value.</p><Button variant="outline" onClick={()=>{setBaseline(conflict);setConflict(null);setReview(null);setError('Review your draft and correction reason, then save against the current revision.')}}>Use current revision and keep my draft</Button></section>:null}
      <fieldset className="patient-form-grid" disabled={form.formState.isSubmitting||!!pending||!!conflict}>
        <PatientFormSections openSections={sections} onToggle={id=>setSections(current=>current.includes(id)?current.filter(s=>s!==id):[...current,id])} mrn={baseline?.patient.recordNumber} practice={scope.practiceName}/>
        <aside className="patient-form-aside" aria-label="Registration and duplicate review">
          <section className="surface form-side-card"><h2><Building2 aria-hidden="true"/>Registration details</h2><dl><div><dt>Practice</dt><dd>{scope.practiceName}</dd></div><div><dt>Access location</dt><dd>{scope.siteName}</dd></div><div><dt>Patient ID</dt><dd>{baseline?.patient.recordNumber??'Assigned after saving'}</dd></div><div><dt>Storage</dt><dd>Saved to Practice database</dd></div></dl>{!initial?<div className="form-field"><label htmlFor="save-destination">After saving</label><select id="save-destination" value={destination} onChange={e=>setDestination(e.target.value)}><option value="chart">Patient chart</option><option value="encounter">Encounter setup</option></select><p className="field-hint">Encounter setup requires a separate Start encounter action.</p></div>:<FormField name="changeNote" label="Correction reason" type="textarea" required hint="Recorded with the original and corrected values."/>}</section>
          <section className="surface form-side-card duplicate-card"><h2><Search aria-hidden="true"/>Possible matches</h2><p className="form-help">Checked when you save, against patients assigned to you in this Practice. Other clinicians’ charts are outside this review scope.</p>{review?<><ul className="candidate-list">{review.result.candidates.map(({patient,reason})=><li key={patient.patientId}><strong>{patient.displayName}</strong><span>{patient.recordNumber} · {patient.birthDate??'Birth date unknown'}</span><small>{reason}</small><button type="button" onClick={async()=>{try{await patientApi.open(scope,patient.patientId,createUuid(),review.result.reviewId);navigate(patientPath(scope,patient.patientId))}catch(e){showError(e instanceof Error?e.message:'The chart could not be opened.')}}}>Open existing chart</button></li>)}</ul>{review.result.candidates.length?<label className="form-checkbox duplicate-confirm"><input type="checkbox" checked={review.confirmed} onChange={e=>setReview({...review,confirmed:e.target.checked})}/><span>I reviewed these matches. This is a separate patient record.</span></label>:<p>No matches in this authorized check.</p>}</>:<p className="form-help">Not checked yet. Records are never merged automatically.</p>}</section>
        </aside>
      </fieldset>
      <footer className="patient-save-bar"><div className="save-record-context"><strong>{baseline?.patient.displayName??'New patient'}</strong><span>{baseline?.patient.recordNumber?`${baseline.patient.recordNumber} · `:''}{scope.practiceName}</span></div><div className="save-buttons"><Button variant="outline" onClick={back} disabled={form.formState.isSubmitting||!!pending}>Cancel</Button><Button type="submit" disabled={form.formState.isSubmitting||!!conflict||(!!initial&&!dirty)}><Save aria-hidden="true"/>{form.formState.isSubmitting?'Saving…':pending?'Retry original save':initial?'Save changes':destination==='encounter'?'Save and continue to encounter':'Save patient'}</Button></div></footer>
    </form></PatientIdentifierContext.Provider></FormProvider>
    <ConfirmationDialog confirmLabel={pending?'Return to original save':'Discard changes'} open={blocker.state==='blocked'} title={pending?'Confirm the save before leaving':'Discard unsaved patient changes?'} description={pending?'The server may have saved this patient. Return to the form and retry the original save to confirm its outcome.':'These edits remain in this open form. Leaving discards them.'} onCancel={()=>blocker.state==='blocked'&&blocker.reset()} onConfirm={()=>{if(blocker.state==='blocked'){if(pending)blocker.reset();else blocker.proceed()}}}/>
  </main>
}
