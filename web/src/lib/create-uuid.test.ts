import { afterEach, describe, expect, it, vi } from 'vitest'
import { createUuid } from './create-uuid'

afterEach(() => vi.unstubAllGlobals())

describe('createUuid', () => {
  it('creates a valid version 4 UUID when randomUUID is unavailable on HTTP', () => {
    let next = 0
    vi.stubGlobal('crypto', {
      getRandomValues: (bytes: Uint8Array) => {
        for (let index = 0; index < bytes.length; index++) bytes[index] = next++
        return bytes
      },
    })

    expect(createUuid()).toBe('00010203-0405-4607-8809-0a0b0c0d0e0f')
    expect(createUuid()).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/)
  })
})
