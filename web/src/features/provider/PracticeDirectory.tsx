import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { ArrowRight, Building2, ChevronLeft, ChevronRight, Plus, Search, TriangleAlert } from 'lucide-react'
import { useDeferredValue, useState } from 'react'
import { Link } from 'react-router-dom'
import { Button } from '../../components/ui/button'
import { listPractices } from './practice-administration-client'
import { filterQuery, formatPracticeDate, type PracticeFilter } from './practice-administration-model'
import { PracticeStatus } from './PracticeStatus'
import './practice-administration.css'

const filters: readonly { value: PracticeFilter; label: string }[] = [
  { value: 'ALL', label: 'All practices' },
  { value: 'ACTION_REQUIRED', label: 'Needs attention' },
  { value: 'ACTIVE', label: 'Active' },
  { value: 'SUSPENDED', label: 'Suspended' },
]

export function PracticeDirectory({ onCreate }: { onCreate: () => void }) {
  const [search, setSearch] = useState('')
  const [filter, setFilter] = useState<PracticeFilter>('ALL')
  const [page, setPage] = useState(0)
  const deferredSearch = useDeferredValue(search)
  const statuses = filterQuery(filter)
  const practices = useQuery({
    queryKey: ['provider', 'practices', deferredSearch.trim(), filter, page],
    queryFn: () => listPractices({ search: deferredSearch, ...statuses, page, size: 25 }),
    placeholderData: keepPreviousData,
  })

  function changeFilter(next: PracticeFilter) {
    setFilter(next)
    setPage(0)
  }

  const directory = practices.data
  const hasSearchOrFilter = search.trim().length > 0 || filter !== 'ALL'

  return <div className="practice-directory-page">
    <div className="provider-heading practice-directory-heading">
      <div><h1>Practices</h1><p>Find and manage record-owning Practice workspaces from the control plane.</p></div>
      <Button onClick={onCreate}><Plus aria-hidden="true" />Create practice</Button>
    </div>

    <div className="provider-boundary practice-directory-boundary" role="note">
      <Building2 aria-hidden="true" />
      <p><strong>Tenant administration without patient access.</strong><span>This directory shows operational metadata only. Entering clinical records requires separate Practice authorization.</span></p>
    </div>

    <section className="practice-directory-surface" aria-labelledby="practice-directory-title">
      <div className="practice-directory-toolbar">
        <div>
          <h2 id="practice-directory-title">Practice directory</h2>
          <p>{directory ? `${directory.totalElements} ${directory.totalElements === 1 ? 'workspace' : 'workspaces'} in this view` : 'Loading workspace count…'}</p>
        </div>
        <label className="practice-search-field" htmlFor="practice-directory-search">
          <Search aria-hidden="true" />
          <span className="sr-only">Search practices</span>
          <input
            id="practice-directory-search"
            value={search}
            onChange={event => { setSearch(event.target.value); setPage(0) }}
            placeholder="Search by name, code, or Practice ID"
            autoComplete="off"
          />
        </label>
      </div>

      <div className="practice-filter-row" aria-label="Filter practices">
        {filters.map(item => <button
          key={item.value}
          className={filter === item.value ? 'selected' : ''}
          type="button"
          aria-pressed={filter === item.value}
          onClick={() => changeFilter(item.value)}
        >{item.label}</button>)}
      </div>

      {practices.isError ? <div className="practice-directory-message error" role="alert">
        <TriangleAlert aria-hidden="true" />
        <div><strong>Practice directory could not be loaded</strong><p>{practices.error.message}</p></div>
        <Button variant="outline" onClick={() => void practices.refetch()}>Try again</Button>
      </div> : null}

      {practices.isPending ? <div className="practice-directory-message" role="status">
        <span className="practice-loading-mark" aria-hidden="true" />
        <div><strong>Loading Practice directory…</strong><p>Checking the latest tenant and service states.</p></div>
      </div> : null}

      {directory && directory.items.length === 0 ? <div className="practice-directory-empty">
        <span><Building2 aria-hidden="true" /></span>
        <h3>{hasSearchOrFilter ? 'No practices match this view' : 'No Practice workspaces yet'}</h3>
        <p>{hasSearchOrFilter ? 'Adjust the search or status filter to see other workspaces.' : 'Create the first record-owning Practice and its initial clinic location.'}</p>
        {hasSearchOrFilter ? <Button variant="outline" onClick={() => { setSearch(''); changeFilter('ALL') }}>Clear filters</Button> : <Button onClick={onCreate}><Plus aria-hidden="true" />Create practice</Button>}
      </div> : null}

      {directory && directory.items.length > 0 ? <>
        <div className={`practice-table-wrap ${practices.isFetching ? 'refreshing' : ''}`} aria-busy={practices.isFetching}>
          <table className="practice-table">
            <thead><tr><th scope="col">Practice</th><th scope="col">Status</th><th scope="col">Initial administrator</th><th scope="col">Schema</th><th scope="col">Last updated</th><th scope="col"><span className="sr-only">Open</span></th></tr></thead>
            <tbody>{directory.items.map(practice => <tr key={practice.practiceId}>
              <td data-label="Practice"><Link className="practice-name-link" to={`/provider/practices/${practice.practiceId}`}><span className="practice-table-icon"><Building2 aria-hidden="true" /></span><span><strong>{practice.displayName}</strong><small>{practice.practiceCode}<span>·</span>{practice.practiceId}</small></span></Link></td>
              <td data-label="Status"><PracticeStatus practice={practice} /><small className="practice-secondary-status">Provisioning: {practice.status.toLowerCase().replace('_', ' ')}</small></td>
              <td data-label="Initial administrator"><span className="practice-table-stack"><strong>{practice.administratorName ?? 'Administrator unavailable'}</strong><small>{practice.administratorEmail ?? 'No setup record'}</small></span></td>
              <td data-label="Schema"><span className="practice-schema">{practice.schemaVersion ? `v${practice.schemaVersion}` : 'Not ready'}</span></td>
              <td data-label="Last updated"><time dateTime={practice.updatedAt}>{formatPracticeDate(practice.updatedAt)}</time></td>
              <td className="practice-open-cell"><Link aria-label={`Manage ${practice.displayName}`} to={`/provider/practices/${practice.practiceId}`}><ArrowRight aria-hidden="true" /></Link></td>
            </tr>)}</tbody>
          </table>
        </div>
        <div className="practice-pagination">
          <p>Page {directory.page + 1} of {Math.max(directory.totalPages, 1)}</p>
          <div><Button variant="outline" size="sm" disabled={directory.page === 0} onClick={() => setPage(current => current - 1)}><ChevronLeft aria-hidden="true" />Previous</Button><Button variant="outline" size="sm" disabled={directory.page + 1 >= directory.totalPages} onClick={() => setPage(current => current + 1)}>Next<ChevronRight aria-hidden="true" /></Button></div>
        </div>
      </> : null}
    </section>
  </div>
}
