import { useEffect, useRef, useState, type FormEvent } from 'react'

export default function EmailSignIn({ csrfToken, linking = false, onSignedIn, onLinked, onStepChange }: {
  csrfToken: string
  onStepChange?: (verifying: boolean) => void
  linking?: boolean
  onLinked?: (email: string) => void
  onSignedIn?: () => void
}) {
  const [email, setEmail] = useState('')
  const [challengeId, setChallengeId] = useState('')
  const [code, setCode] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')

  const [cooldown, setCooldown] = useState({ address: '', until: 0 })
  const [now, setNow] = useState(Date.now)
  const [resent, setResent] = useState(false)
  const emailInput = useRef<HTMLInputElement>(null)
  const address = email.trim().toLowerCase()
  const seconds = cooldown.address === address ? Math.max(0, Math.ceil((cooldown.until - now) / 1000)) : 0

  useEffect(() => {
    if (cooldown.until <= Date.now()) return
    const timer = window.setInterval(() => {
      const time = Date.now()
      setNow(time)
      if (time >= cooldown.until) window.clearInterval(timer)
    }, 250)
    return () => window.clearInterval(timer)
  }, [cooldown.until])

  function waitForSend(seconds: number) {
    const time = Date.now()
    setNow(time)
    setCooldown({ address, until: time + seconds * 1000 })
  }

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
        if (response.status === 429) {
          const details = await response.json().catch(() => ({}))
          if (details.limit === 'daily') setError('Daily email limit reached. Try again after midnight UTC.')
          else {
            waitForSend(Number(response.headers.get('Retry-After')) || 60)
            setError('A code was requested recently. Wait before sending another.')
          }
        }
        else if (response.status === 403 || response.status === 401) setError('Your session expired. Reload the page to try again.')
        else if (response.status === 409) setError(linking ? 'This email or account already has a sign-in link.' : 'You’re already signed in. Reload the page to continue.')
        else if (response.status === 400) setError(sendCode ? 'Enter a valid email address.' : 'That code is invalid or expired. Try again or request a new code.')
        else setError('Email sign-in is temporarily unavailable. Try again shortly.')
        return
      }
      if (sendCode) {
        const next = await response.json()
        setResent(Boolean(challengeId))
        waitForSend(60)
        onStepChange?.(true)
        setChallengeId(next.challengeId)
        setCode('')
      } else if (linking) {
        onLinked?.(email.trim().toLowerCase())
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


  return <form className="add-form" onSubmit={handleSubmit}>
    {challengeId ? <>
      <div className="flex flex-wrap items-baseline gap-x-2">
        <p className="hint wrap-anywhere">Sent to <strong>{email.trim()}</strong></p>
        <button className="text-button" type="button" disabled={busy} onClick={() => {
          setChallengeId('')
          setCode('')
          setError('')
          setResent(false)
          onStepChange?.(false)
          requestAnimationFrame(() => emailInput.current?.focus())
        }}>Change email</button>
      </div>
      {resent && <p role="status" className="hint">New code sent. Use the latest email.</p>}
      <label>Verification code
        <input key={challengeId} autoFocus autoComplete="one-time-code" inputMode="numeric" required pattern="[0-9]{6}"
          maxLength={6} value={code} onChange={event => setCode(event.target.value.replace(/\D/g, ''))} />
      </label>
      <button className="secondary" type="submit" disabled={busy || code.length !== 6}>
        {linking ? 'Verify and link' : 'Verify and sign in'}
      </button>
      <button className="text-button justify-self-start" type="button" onClick={() => void submit(true)} disabled={busy || seconds > 0}>
        {seconds ? `Resend in ${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}` : 'Resend code'}
      </button>
    </> : <>
      <label>Email address
        <input ref={emailInput} type="email" autoComplete="email" maxLength={254} value={email} required
          onChange={event => setEmail(event.target.value)} />
      </label>
      <button type="submit" className="secondary" disabled={busy || !email.trim() || seconds > 0}>
        {busy ? 'Sending…' : seconds ? `Send again in ${seconds}s` : 'Send sign-in code'}
      </button>
    </>}
    {error && <p className="error" role="alert">{error}</p>}
  </form>
}
