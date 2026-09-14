import { Check, Copy, Eye, EyeOff, KeyRound, TriangleAlert } from 'lucide-react'
import { useState } from 'react'
import { copyTextToClipboard } from '../../lib/clipboard'
import './temporary-credential.css'

export function TemporaryCredentialPanel({ username, temporaryPassword }: {
  username: string
  temporaryPassword: string
}) {
  const [revealed, setRevealed] = useState(false)
  const [copyState, setCopyState] = useState<'copied' | 'failed' | null>(null)

  async function copyPassword() {
    try {
      await copyTextToClipboard(temporaryPassword)
      setCopyState('copied')
    } catch {
      setCopyState('failed')
    }
  }

  return <section className="temporary-credential" id="temporary-credential-panel" tabIndex={-1} aria-labelledby="temporary-credential-title">
    <div className="temporary-credential-heading"><KeyRound aria-hidden="true" /><div><strong id="temporary-credential-title">Temporary sign-in details</strong><p>Give these directly to the administrator during personal setup.</p></div></div>
    <dl>
      <div><dt>Username</dt><dd>{username}</dd></div>
      <div><dt>Temporary password</dt><dd><code>{revealed ? temporaryPassword : '••••••••••••••••••••'}</code><span className="temporary-credential-actions"><button type="button" onClick={() => setRevealed(value => !value)} aria-label={revealed ? 'Hide temporary password' : 'Reveal temporary password'}>{revealed ? <EyeOff aria-hidden="true" /> : <Eye aria-hidden="true" />}{revealed ? 'Hide' : 'Reveal'}</button><button type="button" onClick={() => void copyPassword()}><Copy aria-hidden="true" />Copy</button></span></dd></div>
    </dl>
    <p className="temporary-credential-warning"><TriangleAlert aria-hidden="true" /><span><strong>Copy this password now—it is shown only once.</strong> The administrator must replace it at first sign-in. Issuing another temporary password invalidates this one.</span></p>
    {copyState ? <p className={`temporary-copy-status ${copyState}`} role="status">{copyState === 'copied' ? <Check aria-hidden="true" /> : <TriangleAlert aria-hidden="true" />}{copyState === 'copied' ? 'Temporary password copied.' : 'Copy failed. Reveal and copy it manually.'}</p> : null}
  </section>
}
