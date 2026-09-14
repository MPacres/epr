import { describe, expect, it } from 'vitest'
import { portalLabel, portalSurfaceForHostname } from './portal-surface'

describe('portal surface host routing', () => {
  it('maps the local apex domain to the clinical application', () => {
    expect(portalSurfaceForHostname('epr.test')).toBe('clinical')
    expect(portalLabel('clinical')).toBe('Clinical workspace')
  })

  it('maps the support subdomain to provider support', () => {
    expect(portalSurfaceForHostname('support.epr.test')).toBe('support')
    expect(portalLabel('support')).toBe('Provider support')
  })

  it('keeps localhost and direct-IP development in combined mode', () => {
    expect(portalSurfaceForHostname('localhost')).toBe('combined')
    expect(portalSurfaceForHostname('127.0.0.1')).toBe('combined')
  })

  it('supports explicit deployed hostnames without trusting arbitrary subdomains', () => {
    expect(portalSurfaceForHostname('support.example.com', 'example.com', 'support.example.com')).toBe('support')
    expect(portalSurfaceForHostname('support.attacker.test', 'example.com', 'support.example.com')).toBe('combined')
  })
})
