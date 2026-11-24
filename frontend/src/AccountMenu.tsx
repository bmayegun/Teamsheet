import { useRef, useState, type FormEvent } from 'react'
import EmailSignIn from './EmailSignIn'
import type { Session } from './useSavedRoster'

export default function AccountMenu({ session }: { session: Session }) {
  const [name, setName] = useState(session.name ?? '')
  const [draft, setDraft] = useState(name)
  const [email, setEmail] = useState(session.linkedEmail)
  const [editing, setEditing] = useState(false)
  const [busy, setBusy] = useState(false)
  const [notice, setNotice] = useState('')
  const editButton = useRef<HTMLButtonElement>(null)

  function finishEditing() {
    setEditing(false)
    requestAnimationFrame(() => editButton.current?.focus())
  }

  async function save(event: FormEvent) {
    event.preventDefault()
    if (busy || !draft.trim() || !session.csrfToken) return
    setBusy(true)
    setNotice('')
    try {
      const res = await fetch('/api/account/display-name', {
        method: 'PUT', headers: { 'Content-Type': 'application/json', 'X-CSRF-TOKEN': session.csrfToken },
        body: JSON.stringify({ name: draft.trim() }),
      })
      if (!res.ok) throw new Error()
      setName((await res.json()).name)
      finishEditing()
      setNotice('Display name saved.')
    } catch { setNotice('Could not save your name. Please try again.') }
    finally { setBusy(false) }
  }

  return <>
    <button className="account-trigger" type="button" popoverTarget="account-panel" aria-label={`Account options for ${name}`}>
      <span className="truncate">{name}</span><span aria-hidden="true">▾</span>
    </button>
    <section id="account-panel" popover="auto" className="account-panel" aria-label="Account options">
      <header className="account-identity">
        <h2>{name}</h2>
        {email && <p>{email}</p>}
      </header>
      {editing ? <form className="account-edit" onSubmit={save}>
        <label>Display name<input autoFocus maxLength={100} required value={draft} disabled={busy}
          onChange={e => setDraft(e.target.value)} /></label>
        <small>Shown to organisers in all your clubs.</small>
        <div className="flex gap-3"><button className="secondary" disabled={busy || !draft.trim()}>{busy ? 'Saving…' : 'Save name'}</button>
          <button type="button" className="text-button" disabled={busy} onClick={finishEditing}>Cancel</button></div>
      </form> : <button ref={editButton} type="button" className="account-action" onClick={() => {
        setDraft(name); setNotice(''); setEditing(true)
      }}>Edit display name</button>}
      {notice && <p className="account-feedback" role="status">{notice}</p>}
      <details className="account-methods">
        <summary className="account-action">Sign-in methods</summary>
        <div className="account-methods-body">
          <p>Signed in with {session.signInMethod === 'email' ? 'an email code' : 'Google'}.</p>
          {email && session.signInMethod !== 'email' && <p role="status">Email code sign-in is also enabled.</p>}
          {!email && session.canLinkEmail && session.csrfToken && <details>
            <summary className="text-button">Link email sign-in</summary>
            <EmailSignIn linking csrfToken={session.csrfToken} onLinked={setEmail} />
          </details>}
        </div>
      </details>
      <form action="/logout" method="post"><input type="hidden" name="_csrf" value={session.csrfToken ?? ''} />
        <button className="account-action" type="submit">Sign out</button></form>
    </section>
  </>
}
