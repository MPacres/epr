import type { UseFormRegister } from 'react-hook-form'
import { Button } from '../../components/ui/button'
import type { Episode } from './services/encounter-api'
import type { EncounterStartValues } from './services/encounter-setup'

function recordedAt(value: string) {
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? 'Recording time unavailable' : `Recorded ${new Intl.DateTimeFormat('en-PH', { timeZone: 'Asia/Manila', dateStyle: 'medium', timeStyle: 'short' }).format(date)} PHT`
}

const statusLabel: Record<string, string> = { ONGOING: 'Ongoing', UNCERTAIN: 'Uncertain', COMPLETED: 'Completed' }

export function PregnancyEpisodeList({ episodes, loading, failed, refreshing, selected, register, error, onRetry }: {
  episodes: Episode[] | undefined
  loading: boolean
  failed: boolean
  refreshing: boolean
  selected: string
  register: UseFormRegister<EncounterStartValues>
  error?: string
  onRetry: () => void
}) {
  return <div className="pregnancy-episode-field" role="group" aria-labelledby="pregnancy-episode-label" aria-describedby="pregnancy-episode-hint">
    <div className="pregnancy-episode-heading"><span id="pregnancy-episode-label">Pregnancy episode <span className="required-indicator">Required</span></span>{episodes && !failed ? <span className="pregnancy-episode-count">{episodes.length} recorded</span> : null}</div>
    <p id="pregnancy-episode-hint">Choose the record for this pregnancy. Check its source and status before starting care.</p>
    {loading ? <p className="pregnancy-episode-state" role="status">Loading pregnancy episodes…</p> : null}
    {failed ? <div className="pregnancy-episode-state clinical-error" role="alert"><p>Pregnancy episodes could not be loaded. Check access or connection, then try again.</p><Button type="button" variant="outline" onClick={onRetry}>Retry loading episodes</Button></div> : null}
    {!loading && !failed && episodes?.length === 0 ? <p className="pregnancy-episode-state">No pregnancy episode is recorded for this patient at this Practice. Record a sourced episode below before starting this visit.</p> : null}
    {!failed && episodes && episodes.length > 0 ? <div className="pregnancy-episode-list" role="radiogroup" aria-label="Pregnancy episodes" aria-required="true" aria-invalid={!!error}>
      {episodes.map(episode => <label key={episode.episodeId} className={`pregnancy-episode-row${selected === episode.episodeId ? ' selected' : ''}`}>
        <input type="radio" value={episode.episodeId} {...register('episodeId')} />
        <span className="pregnancy-episode-copy"><span className="pregnancy-episode-title"><strong>{statusLabel[episode.status] ?? 'Status unavailable'}</strong><small>{recordedAt(episode.receivedAt)}</small></span><span className="pregnancy-episode-source">{episode.source}</span><small>{episode.selectedDatingId ? 'Dating basis selected' : 'No dating basis selected'}</small></span>
      </label>)}
    </div> : null}
    {refreshing && !loading ? <p className="pregnancy-episode-refresh" role="status">Refreshing pregnancy episodes…</p> : null}
    {error ? <p className="pregnancy-episode-validation" role="alert">{error}</p> : null}
  </div>
}
