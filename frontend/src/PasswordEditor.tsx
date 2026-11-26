import { useEffect, useState, type FormEvent } from 'react'
import EmailSignIn from './EmailSignIn'
import type { Session } from './useSavedRoster'

export default function PasswordEditor({ csrfToken, requireCurrent = false, onSaved }: {
  csrfToken: string
  requireCurrent?: boolean
  onSaved: () => void
}) {
  const [password, setPassword] = useState('')
  const [currentPassword, setCurrentPassword] = useState('')
  const [confirmation, setConfirmation] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [visible, setVisible] = useState(false)
  const [account, setAccount] = useState<Session>()
  const [checking, setChecking] = useState(true)
  const [checkFailed, setCheckFailed] = useState(false)
  const [usingEmailProof, setUsingEmailProof] = useState(!requireCurrent)
  const [now, setNow] = useState(Date.now)

  async function refreshVerification(signal?: AbortSignal) {
    setChecking(true)
    setCheckFailed(false)
    setError('')
    try {
      const response = await fetch('/api/session', { cache: 'no-store', signal })
      const next: Session = await response.json()
      if (!response.ok || !next.name || !next.csrfToken) throw new Error()
      if (signal?.aborted) return
      setAccount(next)
      setNow(Date.now())
      if (next.passwordVerificationExpiresAt) setUsingEmailProof(true)
    } catch {
      if (!signal?.aborted) {
        setCheckFailed(true)
        setError('Could not check your session. Retry to continue.')
      }
    } finally {
      if (!signal?.aborted) setChecking(false)
    }
  }

  useEffect(() => {
    const controller = new AbortController()
    void refreshVerification(controller.signal)
    return () => controller.abort()
  }, [])

  const expiresAt = account?.passwordVerificationExpiresAt ?? null
  const expired = usingEmailProof && (!expiresAt || now >= expiresAt)
  const needsCurrent = !usingEmailProof && requireCurrent

  useEffect(() => {
    if (!usingEmailProof || !expiresAt) return
    const update = () => setNow(Date.now())
    const timer = window.setInterval(update, 1000)
    window.addEventListener('focus', update)
    document.addEventListener('visibilitychange', update)
    return () => {
      window.clearInterval(timer)
      window.removeEventListener('focus', update)
      document.removeEventListener('visibilitychange', update)
    }
  }, [usingEmailProof, expiresAt])

  async function save(event: FormEvent) {
    event.preventDefault()
    if (busy || checking || expired || !account || checkFailed) return
    if (password !== confirmation) { setError('The passwords do not match.'); return }
    setBusy(true)
    setError('')
    try {
      const response = await fetch('/api/account/password', {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json', 'X-CSRF-TOKEN': account.csrfToken ?? csrfToken },
        body: JSON.stringify({ password, currentPassword: needsCurrent ? currentPassword : undefined }),
      })
      if (response.headers.get('Password-Verification-Expired') === 'true') {
        setAccount(current => current && { ...current, passwordVerificationExpiresAt: Date.now() - 1 })
        setUsingEmailProof(true)
      }
      if (!response.ok) {
        if (response.status === 429) setError('Too many attempts. Try again in 15 minutes.')
        else if (response.status === 403) {
          const details = await response.json().catch(() => ({}))
          if (details.code === 'password_verification_required') {
            setAccount(current => current && { ...current, passwordVerificationExpiresAt: Date.now() - 1 })
            setUsingEmailProof(true)
          } else setError('Your current password is incorrect. Try again or verify your email instead.')
        }
        else if (response.status === 401) setError('Your session expired. Sign in again.')
        else if (response.status === 422) setError('This password is too common or has appeared in a data breach. Choose a different one.')
        else if (response.status === 503) setError('Password safety check is temporarily unavailable. Try saving again shortly.')
        else if (response.status === 400) setError('Use between 8 and 128 characters.')
        else setError('Could not save your password. Reload and try again.')
        return
      }
      setPassword('')
      setCurrentPassword('')
      setConfirmation('')
      onSaved()
    } catch { setError('Could not reach TeamSheet. Try again.') }
    finally { setBusy(false) }
  }

  return <>
    {checking ? <p className="hint" role="status">Checking your verification…</p>
      : checkFailed ? <div className="password-recovery">
        <p className="hint" role="alert">{error || 'Could not check your session. Retry to continue.'}</p>
        <button className="secondary" type="button" onClick={() => void refreshVerification()}>Retry session check</button>
      </div>
      : expired ? <section className="password-recovery" aria-labelledby="password-recovery-title">
        <h3 id="password-recovery-title">Verify your email</h3>
        <p className="hint" role="status">
          {expiresAt ? 'Your email verification has expired.' : 'Email verification is required.'} Verify again to save your password. Your entries will stay here.
        </p>
        {account?.emailSignInEnabled && account.linkedEmail
          ? <EmailSignIn initialEmail={account.linkedEmail} linking csrfToken={account.csrfToken ?? csrfToken}
              verificationLabel="Verify email" onLinked={() => void refreshVerification()} />
          : <p className="hint">Email verification is currently unavailable. Try again later.</p>}
      </section>
      : <form className="add-form password-form" onSubmit={save}>
        {needsCurrent && <label>Current password
          <input type={visible ? 'text' : 'password'} required maxLength={128} autoComplete="current-password"
            value={currentPassword} onChange={e => setCurrentPassword(e.target.value)} />
        </label>}
        <label>New password
          <input autoFocus type={visible ? 'text' : 'password'} required minLength={8} maxLength={128}
            autoComplete="new-password" aria-describedby="password-length" value={password} onChange={e => setPassword(e.target.value)} />
        </label>
        <p className="hint" id="password-length">At least 8 characters. Longer passwords are stronger. Spaces are welcome.</p>
        <label>Confirm new password
          <input type={visible ? 'text' : 'password'} required minLength={8} maxLength={128} autoComplete="new-password"
            value={confirmation} onChange={e => setConfirmation(e.target.value)} />
        </label>
        <label className="password-visibility"><input type="checkbox" checked={visible} onChange={e => setVisible(e.target.checked)} />Show passwords</label>
        {usingEmailProof && <p className="hint" role="status">Email verified. Save your password before {new Date(expiresAt!).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}.</p>}
        {needsCurrent && account?.emailSignInEnabled && <button className="text-button" type="button" onClick={() => setUsingEmailProof(true)}>Verify email instead</button>}
        <button className="primary" disabled={busy || checking || expired || !account || checkFailed || !password || !confirmation}>{busy ? 'Saving…' : 'Save password'}</button>
        {error && <p role="alert" className="error">{error}</p>}
      </form>}
  </>
}
