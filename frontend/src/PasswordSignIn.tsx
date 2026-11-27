import { useState, type FormEvent } from 'react'
import EmailSignIn from './EmailSignIn'
import PasswordEditor from './PasswordEditor'

type Mode = 'login' | 'code' | 'register' | 'recover' | 'set'
export default function PasswordSignIn({ csrfToken, emailEnabled, onDone, onStage, onGoogleSignIn }: {
  csrfToken: string
  emailEnabled: boolean
  onDone: () => void
  onStage: (title: string, hideMethods: boolean) => void
  onGoogleSignIn: () => void
}) {
  const [mode, setMode] = useState<Mode>('login')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [passwordVisible, setPasswordVisible] = useState(false)
  const [token, setToken] = useState(csrfToken)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')

  function changeMode(next: Mode) {
    setMode(next)
    setError('')
    setPassword('')
    setPasswordVisible(false)
    onStage(next === 'recover' ? 'Reset your password' : next === 'register' ? 'Create your account' :
      next === 'code' ? 'Sign in with an email code' : next === 'set' ? 'Set your password' : 'Sign in to TeamSheet', next !== 'login')
  }

  async function verified() {
    setBusy(true)
    try {
      const response = await fetch('/api/session', { cache: 'no-store' })
      const session = await response.json()
      if (!response.ok || !session.name || !session.csrfToken) throw new Error()
      setToken(session.csrfToken)
      changeMode('set')
    } catch { setError('You are signed in. Reload and set your password under Account → Sign-in methods.') }
    finally { setBusy(false) }
  }

  async function login(event: FormEvent) {
    event.preventDefault()
    if (busy) return
    setBusy(true)
    setError('')
    try {
      const response = await fetch('/api/auth/password', {
        method: 'POST', headers: { 'Content-Type': 'application/json', 'X-CSRF-TOKEN': token },
        body: JSON.stringify({ email, password }),
      })
      if (!response.ok) {
        if (response.status === 429) setError('Too many attempts. Try again in 15 minutes.')
        else if (response.status === 401) setError('Email or password is incorrect. Try again or reset your password.')
        else if (response.status === 403) setError('Your session changed. Reload and try again.')
        else setError('Could not sign in. Reload and try again.')
        return
      }
      setPassword('')
      onDone()
    } catch { setError('Could not reach TeamSheet. Try again.') }
    finally { setBusy(false) }
  }

  if (mode === 'set') return <>
    <p className="hint">Your email is verified. Save a password to use for future sign-ins.</p>
    <PasswordEditor csrfToken={token} onSaved={onDone} />
    <button type="button" className="text-button mt-3" onClick={onDone}>Continue without a password</button>
  </>

  return <>
    {mode === 'login' ? <>
      <form className="add-form password-form" onSubmit={login}>
        <label>Email address<input type="email" autoComplete="username" required maxLength={254}
          value={email} onChange={e => setEmail(e.target.value)} /></label>
        <div className="password-input-field">
          <label htmlFor="sign-in-password">Password</label>
          <div className="password-input-row">
            <input id="sign-in-password" type={passwordVisible ? 'text' : 'password'} autoComplete="current-password" required maxLength={128}
              value={password} onChange={e => setPassword(e.target.value)} />
            <button type="button" className="password-visibility-toggle"
              aria-label={passwordVisible ? 'Hide password' : 'Show password'} aria-pressed={passwordVisible}
              onClick={() => setPasswordVisible(visible => !visible)}>
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                <path d="M2.5 12s3.2-6 9.5-6 9.5 6 9.5 6-3.2 6-9.5 6-9.5-6-9.5-6Z" />
                <circle cx="12" cy="12" r="2.5" />
                {passwordVisible && <path d="M4 4l16 16" />}
              </svg>
            </button>
          </div>
        </div>
        <button className="primary" disabled={busy}>{busy ? 'Signing in…' : 'Sign in'}</button>
      </form>
      {emailEnabled ? <div className="sign-in-alternatives">
        <button type="button" className="text-button" onClick={() => changeMode('recover')}>Forgot password?</button>
        <button type="button" className="text-button" onClick={() => changeMode('register')}>Create an account</button>
        <button type="button" className="text-button" onClick={() => changeMode('code')}>Use an email code instead</button>
      </div> : <div className="sign-in-alternatives">
        <a className="text-button" href="/oauth2/authorization/google" onClick={onGoogleSignIn}>Create an account with Google</a>
      </div>}
    </> : <>
      {mode === 'recover' && <p className="hint">Verify your email with a code, then choose a new password.</p>}
      {mode === 'register' && <p className="hint">Verify your email first. Your saved players and clubs will stay with this account.</p>}
      <EmailSignIn key={mode} verificationLabel={mode === 'code' ? undefined : 'Verify and continue'} csrfToken={token} initialEmail={email} onSignedIn={mode === 'code' ? onDone : verified}
        onStepChange={verifying => onStage(verifying ? 'Enter your sign-in code' :
          mode === 'recover' ? 'Reset your password' : mode === 'register' ? 'Create your account' : 'Sign in with an email code', true)} />
      <button type="button" className="text-button mt-3" disabled={busy} onClick={() => changeMode('login')}>Back to sign in</button>
    </>}
    {error && <p className="error" role="alert">{error}</p>}
  </>
}
