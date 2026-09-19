export type SaveState = 'saved' | 'unsaved' | 'saving' | 'failed' | 'conflict'
export type SaveReceipt<T> = { value: T; version: number; savedAt: string | null }
export type PendingSave<T> = { value: T; baseline: T; version: number; key: string; clientTime: string }
const equal = (a: unknown, b: unknown) => JSON.stringify(a) === JSON.stringify(b)

/** Exactly one frozen command at a time. Retry never adopts newer edits or a new key. */
export class DraftWriter<T> {
  state: SaveState = 'saved'
  desired: T
  acknowledged: T
  version: number
  savedAt: string | null
  pending: PendingSave<T> | null = null
  error: unknown = null
  private running: Promise<void> | null = null
  private disposed = false
  private save: (request: PendingSave<T>) => Promise<SaveReceipt<T>>
  private key: () => string
  private notify: () => void
  constructor(initial: SaveReceipt<T>, save: (request: PendingSave<T>) => Promise<SaveReceipt<T>>,
    key: () => string, notify: () => void) {
    this.save = save; this.key = key; this.notify = notify
    this.desired = initial.value; this.acknowledged = initial.value; this.version = initial.version; this.savedAt = initial.savedAt
  }
  get dirty() { return this.pending !== null || !equal(this.desired, this.acknowledged) }
  edit(value: T) { this.desired = value; if (!['saving', 'failed', 'conflict'].includes(this.state) || this.state === 'failed' && this.pending === null) this.state = this.dirty ? 'unsaved' : 'saved'; this.notify() }
  async flush(): Promise<void> {
    if (this.disposed || this.state === 'conflict') return
    if (this.running) return this.running
    if (!this.dirty) return
    this.pending ??= { value: structuredClone(this.desired), baseline: structuredClone(this.acknowledged), version: this.version, key: this.key(), clientTime: new Date().toISOString() }
    const pending = this.pending
    this.state = 'saving'; this.error = null; this.notify()
    this.running = this.save(pending).then(receipt => {
      if (this.disposed) return
      if (equal(this.desired, pending.value)) this.desired = receipt.value
      this.acknowledged = receipt.value; this.version = receipt.version; this.savedAt = receipt.savedAt; this.pending = null
      this.state = this.dirty ? 'unsaved' : 'saved'
    }).catch(error => {
      if (this.disposed) return
      this.error = error
      if (typeof error === 'object' && error !== null && 'status' in error && [400, 422].includes(Number(error.status))) this.pending = null
      this.state = typeof error === 'object' && error !== null && 'status' in error && error.status === 409 ? 'conflict' : 'failed'
    }).finally(() => { this.running = null; if (!this.disposed) this.notify() })
    return this.running
  }
  resolve(server: SaveReceipt<T>, selected: T) {
    if (this.running) throw new Error('Wait for the current save.')
    this.acknowledged = server.value; this.version = server.version; this.savedAt = server.savedAt
    this.desired = selected; this.pending = null; this.error = null; this.state = this.dirty ? 'unsaved' : 'saved'; this.notify()
  }
  activate() { this.disposed = false }
  dispose() { this.disposed = true }
}
