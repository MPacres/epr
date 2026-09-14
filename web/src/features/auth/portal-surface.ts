export type PortalSurface = 'clinical' | 'support' | 'combined'

export function portalSurfaceForHostname(
  hostname: string,
  clinicalHostname = 'epr.test',
  supportHostname = 'support.epr.test',
): PortalSurface {
  const normalized = hostname.trim().toLowerCase()
  if (normalized === supportHostname.trim().toLowerCase()) return 'support'
  if (normalized === clinicalHostname.trim().toLowerCase()) return 'clinical'
  return 'combined'
}

export function portalLabel(surface: PortalSurface): string {
  if (surface === 'clinical') return 'Clinical workspace'
  if (surface === 'support') return 'Provider support'
  return 'Provider portal'
}
