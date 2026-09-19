import { useQuery } from '@tanstack/react-query'
import { z } from 'zod'
import { useNavigate } from 'react-router-dom'
import { Button } from '../../components/ui/button'
import { atSite, clinicalKey, request, type Scope } from '../encounters/services/encounter-api'
import { reportSchema } from './report-schema'
export function IncomingReportInbox({ scope }: { scope: Scope }) {
  const navigate = useNavigate()
  const reports = useQuery({ queryKey: [...clinicalKey(scope), 'unmatched-reports'], queryFn: ({ signal }) => request(atSite(scope, '/incoming-service-reports'), z.array(reportSchema), { signal }), retry: false })
  return <section className="surface encounter-interventions"><h2>Reports awaiting patient matching</h2>{reports.isPending ? <p role="status">Loading received reports…</p> : reports.error ? <p role="alert">Report matching work is unavailable in this scope.</p> : !reports.data?.length ? <p>No unmatched reports in your authorized scope.</p> : <><p>You retain ownership of these reports. Open the intended patient’s encounter, review the request, then choose “Receive / match report”.</p>{reports.data.map(report => <div className="clinical-directory-row" key={report.id}><span><strong>{report.source}</strong><small>{report.externalReference} · Received {new Date(report.receivedAt).toLocaleString('en-PH', { timeZone: 'Asia/Manila' })} PHT</small></span><span className="badge amber">Unmatched</span></div>)}<Button variant="outline" onClick={() => navigate('/patients')}>Find the intended patient</Button></>}</section>
}
