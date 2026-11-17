import { useState, type FormEvent } from 'react'

export default function EmailSignIn({ csrfToken, linking = false, onSignedIn }: {
  csrfToken: string
  linking?: boolean
  onSignedIn?: () => void
}) {
  const [email, setEmail] = useState('')
  const [challengeId, setChallengeId] = useState('')
  const [code, setCode] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [linked, setLinked] = useState(false)

  async function submit(sendCode: boolean) {
    setBusy(true)
    setError('')
    try {
      const response = await fetch(sendCode ? '/api/auth/email/request' : '/api/auth/email/verify', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'X-CSRF-TOKEN': csrfToken },
        body: JSON.stringify(sendCode ? { email, linking } : { challengeId, code, linking }),
      })
      if (!response.ok) {
        if (response.status === 429) setError('Please wait before requesting another code.')
        else if (response.status === 403 || response.status === 401) setError('Your session expired. Reload the page to try again.')
        else if (response.status === 409) setError(linking ? 'This email or account already has a sign-in link.' : 'You’re already signed in. Reload the page to continue.')
        else if (response.status === 400) setError(sendCode ? 'Enter a valid email address.' : 'That code is invalid or expired. Try again or request a new code.')
        else setError('Email sign-in is temporarily unavailable. Try again shortly.')
        return
      }
      if (sendCode) {
        const next = await response.json()
        setChallengeId(next.challengeId)
        setCode('')
      } else if (linking) {
        setLinked(true)
      } else {
        onSignedIn?.()
      }
    } catch {
      setError('Could not reach TeamSheet. Check your connection and try again.')
    } finally {
      setBusy(false)
    }
  }

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!busy) void submit(!challengeId)
  }

  if (linked) return <p className="hint" role="status">Email sign-in is linked. You can use either method to open this roster.</p>

  return <form className="add-form" onSubmit={handleSubmit}>
    {challengeId ? <>
      <p role="status" className="hint wrap-anywhere">Enter the six-digit code we sent to {email.trim()}.</p>
      <label>Verification code
        <input key={challengeId} autoFocus autoComplete="one-time-code" inputMode="numeric" required pattern="[0-9]{6}"
          maxLength={6} value={code} onChange={event => setCode(event.target.value.replace(/\D/g, ''))} />
      </label>
      <button className="secondary" type="submit" disabled={busy || code.length !== 6}>
        {linking ? 'Verify and link' : 'Verify and sign in'}
      </button>
      <div className="flex flex-wrap gap-x-5">
        <button className="text-button" type="button" onClick={() => void submit(true)} disabled={busy}>Resend code</button>
        <button className="text-button" type="button" disabled={busy} onClick={() => {
          setChallengeId('')
          setCode('')
          setError('')
        }}>Change email</button>
      </div>
    </> : <>
      <label>Email address
        <input type="email" autoComplete="email" maxLength={254} value={email} required
          onChange={event => setEmail(event.target.value)} />
      </label>
      <button type="submit" className="secondary" disabled={busy || !email.trim()}>
        {busy ? 'Sending…' : 'Send sign-in code'}
      </button>
    </>}
    {error && <p className="error" role="alert">{error}</p>}
  </form>
}
