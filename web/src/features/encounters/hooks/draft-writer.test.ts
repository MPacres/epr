import { describe, it, expect, vi } from 'vitest'
import { DraftWriter, type PendingSave, type SaveReceipt } from './draft-writer'
function deferred<T>() { let resolve!: (value: T) => void; let reject!: (reason: unknown) => void; const promise = new Promise<T>((a, b) => { resolve = a; reject = b }); return { promise, resolve, reject } }
describe('serialized server draft acknowledgments', () => {
  it('never marks edits typed during a request as saved and serializes flushes', async () => {
    const response = deferred<SaveReceipt<string>>()
    const save = vi.fn(() => response.promise)
    const writer = new DraftWriter({ value: 'baseline', version: 0, savedAt: null }, save, () => 'key-1', vi.fn())
    writer.edit('first'); const first = writer.flush(); writer.edit('newer'); void writer.flush()
    expect(save).toHaveBeenCalledTimes(1)
    response.resolve({ value: 'first', version: 1, savedAt: 'server-time' }); await first
    expect(writer.acknowledged).toBe('first'); expect(writer.desired).toBe('newer'); expect(writer.state).toBe('unsaved')
  })
  it('retries the identical frozen payload and key after timeout despite further typing', async () => {
    const calls: PendingSave<string>[] = []
    const save = vi.fn(async (pending: PendingSave<string>) => { calls.push(pending); if (calls.length === 1) throw new Error('timeout'); return { value: pending.value, version: 1, savedAt: 'now' } })
    const writer = new DraftWriter({ value: 'baseline', version: 0, savedAt: null }, save, () => 'key-1', vi.fn())
    writer.edit('first'); await writer.flush(); writer.edit('second'); await writer.flush()
    expect(calls[0]).toEqual(calls[1]); expect(writer.desired).toBe('second'); expect(writer.dirty).toBe(true)
  })
  it('pauses on conflict and requires explicit server/local resolution', async () => {
    const save = vi.fn(async () => { throw { status: 409 } })
    const writer = new DraftWriter({ value: 'baseline', version: 0, savedAt: null }, save, () => 'key-1', vi.fn())
    writer.edit('local'); await writer.flush(); await writer.flush()
    expect(save).toHaveBeenCalledTimes(1); expect(writer.desired).toBe('local')
    writer.resolve({ value: 'server edit', version: 2, savedAt: 'now' }, 'local')
    expect(writer.version).toBe(2); expect(writer.state).toBe('unsaved'); expect(writer.pending).toBeNull()
  })
  it('allows correction after a rejected validation command without replaying invalid data', async () => {
    let nextKey = 0
    const save = vi.fn(async (pending: PendingSave<string>) => { if (pending.value === 'invalid') throw { status: 422 }; return { value: pending.value, version: 1, savedAt: 'now' } })
    const writer = new DraftWriter({ value: 'baseline', version: 0, savedAt: null }, save, () => `key-${++nextKey}`, vi.fn())
    writer.edit('invalid'); await writer.flush(); expect(writer.pending).toBeNull()
    writer.edit('corrected'); expect(writer.state).toBe('unsaved'); await writer.flush()
    expect(writer.acknowledged).toBe('corrected'); expect(save.mock.calls[0][0].key).not.toBe(save.mock.calls[1][0].key)
  })

})
