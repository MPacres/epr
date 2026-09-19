import { describe, expect, it } from 'vitest'
import { executeConfirmedSteps } from './command-sequence'
describe('multi-step clinical commands', () => {
  it('does not repeat acknowledged receipt when matching has an uncertain outcome', async () => {
    const steps = [{ key: 'receipt', body: { reportId: 'original-id' } }, { key: 'match', body: { requestVersion: 3 } }]
    let next = 0; const sent: typeof steps = []
    await expect(executeConfirmedSteps(steps, next, async step => { sent.push(step); if (step.key === 'match') throw Error('Response lost') }, value => { next = value; return true })).rejects.toThrow('Response lost')
    expect(next).toBe(1)
    await executeConfirmedSteps(steps, next, async step => { sent.push(step); return 'confirmed' }, value => { next = value; return true })
    expect(sent.map(s => s.key)).toEqual(['receipt', 'match', 'match'])
    expect(sent[2]).toBe(sent[1])
    expect(next).toBe(2)
  })
  it('stops subsequent mutations when the initiating patient workspace has gone away', async () => {
    const sent: string[] = []
    const result = await executeConfirmedSteps(['safety-review', 'prescription'], 0, async step => { sent.push(step); return 'confirmed' }, () => false)
    expect(sent).toEqual(['safety-review'])
    expect(result).toBeUndefined()
  })
})
