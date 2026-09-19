/** Advance only after server acknowledgement; retry the same failed step and key. */
export async function executeConfirmedSteps<T>(steps: readonly T[], start: number, send: (step: T) => Promise<unknown>, acknowledge: (next: number) => boolean): Promise<unknown> {
  let result: unknown
  for (let index = start; index < steps.length; index++) {
    result = await send(steps[index])
    if (!acknowledge(index + 1)) return undefined
  }
  return result
}
