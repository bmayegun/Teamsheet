import { useEffect, useRef, useState } from 'react'
import ClubMembers from './ClubMembers'

interface Club { id: string; name: string; role: 'OWNER' | 'ORGANISER' }
type Panel = 'rename' | 'create' | 'join' | 'invite' | 'members' | null

function pendingInvitation() {
  const fromUrl = new URLSearchParams(location.hash.slice(1)).get('club-invite')
  try { return fromUrl ?? sessionStorage.getItem('teamsheet-club-invite') ?? '' }
  catch { return fromUrl ?? '' }
}

export default function ClubWorkspaces({ csrfToken, clubId, busy, dirty, onSelect }: {
  csrfToken: string
  clubId: string | null
  busy: boolean
  dirty: boolean
  onSelect: (clubId: string | null) => Promise<boolean>
}) {
  const [clubs, setClubs] = useState<Club[]>([])
  const [personalName, setPersonalName] = useState('My players')
  const [editedName, setEditedName] = useState('')
  const [name, setName] = useState('')
  const [invite, setInvite] = useState('')
  const [copied, setCopied] = useState(false)
  const [invitationPreview, setInvitationPreview] = useState<{clubId: string; name: string; role: string} | null>(null)
  const [joinLink, setJoinLink] = useState(pendingInvitation)
  const [panel, setPanel] = useState<Panel>(() => pendingInvitation() ? 'join' : null)
  const [pending, setPending] = useState(false)
  const [message, setMessage] = useState('')
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(true)
  const [loaded, setLoaded] = useState(false)
  const options = useRef<HTMLDetailsElement>(null)
  const optionsSummary = useRef<HTMLElement>(null)
  const selectedClub = clubs.find(club => club.id === clubId)
  const invitationMembership = clubs.find(club => club.id === invitationPreview?.clubId)
  const editingLocked = busy || pending || loading
  const switchingLocked = editingLocked || dirty

  async function loadWorkspaces(signal?: AbortSignal) {
    setLoading(true)
    setError('')
    try {
      const [clubResponse, personalResponse] = await Promise.all([
        fetch('/api/clubs', { signal }), fetch('/api/clubs/personal-workspace', { signal }),
      ])
      if (!clubResponse.ok || !personalResponse.ok) throw new Error()
      const [nextClubs, workspace] = await Promise.all([clubResponse.json(), personalResponse.json()])
      if (signal?.aborted) return
      setClubs(nextClubs)
      if (selectedClub) setClubs(current => current.map(club => club.id === selectedClub.id ? { ...club, name: workspace.name } : club))
      else setPersonalName(workspace.name)
      setLoaded(true)
    } catch {
      if (!signal?.aborted) setError('Could not load your workspaces. Retry to continue.')
    } finally {
      if (!signal?.aborted) setLoading(false)
    }
  }

  useEffect(() => {
    const controller = new AbortController()
    const invitation = pendingInvitation()
    if (invitation) {
      try { sessionStorage.setItem('teamsheet-club-invite', invitation) } catch { /* Manual paste remains available. */ }
      if (new URLSearchParams(location.hash.slice(1)).has('club-invite')) {
        history.replaceState(null, '', location.pathname + location.search)
      }
    }
    void loadWorkspaces(controller.signal)
    return () => controller.abort()
  }, [])

  function openPanel(next: Panel) {
    setPanel(next)
    setError('')
    setMessage('')
    setCopied(false)
    if (next === 'rename') setEditedName(selectedClub?.name ?? personalName)
  }

  function closePanel() {
    setPanel(null)
    setError('')
    optionsSummary.current?.focus()
  }

  async function request(path: string, method: 'POST' | 'PUT', body: object, invalidMessage: string) {
    const response = await fetch(path, {
      method,
      headers: { 'Content-Type': 'application/json', 'X-CSRF-TOKEN': csrfToken },
      body: JSON.stringify(body),
    })
    if (!response.ok) {
      if (response.status === 400) throw new Error(invalidMessage)
      if (response.status === 401) throw new Error('Your session expired. Sign in again to continue.')
      if (response.status === 403) throw new Error('You do not have permission for this action. Sign in again or ask the club owner.')
      throw new Error('Could not complete this action. Try again.')
    }
    return response.json()
  }

  async function renamePersonal() {
    if (editingLocked || !editedName.trim()) return
    setPending(true)
    setError('')
    try {
      const workspace = await request(selectedClub ? '/api/clubs/' + selectedClub.id : '/api/clubs/personal-workspace', 'PUT', { name: editedName },
        'Use a workspace name between 1 and 100 characters.')
      if (selectedClub) setClubs(current => current.map(club => club.id === selectedClub.id ? { ...club, name: workspace.name } : club))
      else setPersonalName(workspace.name)
      setPanel(null)
      setMessage('Workspace name saved.')
      optionsSummary.current?.focus()
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : 'Could not save the name. Try again.')
    } finally {
      setPending(false)
    }
  }

  async function changeWorkspace(next: string | null) {
    if (switchingLocked) return
    setPending(true)
    setError('')
    setMessage('')
    try {
      if (await onSelect(next)) {
        setPanel(null)
        setInvite('')
        setCopied(false)
        if (options.current) options.current.open = false
      }
    } finally {
      setPending(false)
    }
  }

  function invitationToken() {
    const trimmed = joinLink.trim()
    return trimmed.includes('#') ? new URLSearchParams(trimmed.split('#')[1]).get('club-invite') ?? '' : trimmed
  }

  async function previewInvitation() {
    if (switchingLocked || !joinLink.trim()) return
    setPending(true)
    setError('')
    try {
      const preview = await request('/api/clubs/invitations/preview', 'POST', { token: invitationToken() },
        'This invitation is invalid, expired, or already used. Ask the owner for a new link.')
      setInvitationPreview(preview)
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : 'Could not check the invitation.')
    } finally {
      setPending(false)
    }
  }

  async function saveClub(joining: boolean) {
    if (switchingLocked || (joining ? !joinLink.trim() || !invitationPreview : !name.trim())) return
    setPending(true)
    setError('')
    setMessage('')
    try {
      const token = invitationToken()
      const club: Club = joining
        ? await request('/api/clubs/invitations/accept', 'POST', { token: token ?? '' },
          'This invitation is invalid, expired, or already used. Ask the owner for a new link.')
        : await request('/api/clubs', 'POST', { name }, 'Use a club name between 1 and 100 characters.')
      setClubs(current => [...current.filter(item => item.id !== club.id), club])
      if (joining) {
        setInvitationPreview(null)
        setJoinLink('')
        try { sessionStorage.removeItem('teamsheet-club-invite') } catch { /* Browser storage can be unavailable. */ }
      } else setName('')
      setInvite('')
      setPanel(null)
      const switched = await onSelect(club.id)
      setMessage(switched ? (joining ? `Joined ${club.name}.` : `${club.name} created. Add players to start its roster.`) :
        `${club.name} is ready. Save any pending changes, then select it from Workspace.`)
      if (options.current) options.current.open = false
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : 'Could not update the club. Try again.')
    } finally {
      setPending(false)
    }
  }

  async function createInvite() {
    if (editingLocked || selectedClub?.role !== 'OWNER') return
    setPending(true)
    setError('')
    setCopied(false)
    try {
      const { token } = await request('/api/clubs/' + selectedClub.id + '/invitations', 'POST', {},
        'Could not create an invitation. Try again.')
      setInvite(location.origin + '/#club-invite=' + encodeURIComponent(token))
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : 'Could not create an invitation.')
    } finally {
      setPending(false)
    }
  }

  async function copyInvite() {
    try {
      await navigator.clipboard.writeText(invite)
      setCopied(true)
      setError('')
    } catch {
      setError('Clipboard unavailable. Select and copy the invitation link below.')
    }
  }

  return <section className="workspace-toolbar" aria-label="Workspace" aria-busy={editingLocked}>
    <div className="workspace-switcher">
      <label htmlFor="workspace-select">Workspace</label>
      <div className="workspace-switcher-line">
        <select id="workspace-select" value={clubId ?? ''} disabled={switchingLocked || !loaded}
          aria-describedby="workspace-description" onChange={event => void changeWorkspace(event.target.value || null)}>
          <option value="">{loaded ? personalName : loading ? 'Loading workspaces…' : 'Workspaces unavailable'}</option>
          {clubs.map(club => <option key={club.id} value={club.id}>{club.name}</option>)}
        </select>
        <span id="workspace-description" className="workspace-description">
          {selectedClub ? `${selectedClub.role === 'OWNER' ? 'Owner' : 'Organiser'} · Shared players and attendance` : 'Your personal players and attendance'}
        </span>
      </div>
    </div>
    <details className="workspace-options" ref={options} open={panel !== null ? true : undefined}>
      <summary ref={optionsSummary}>Workspace options</summary>
      <div className="workspace-options-body">
        <div className="workspace-option-actions" aria-label="Workspace actions">
          {(!clubId || selectedClub?.role === 'OWNER') && <button className="text-button" type="button" disabled={editingLocked || !loaded}
            aria-pressed={panel === 'rename'} onClick={() => openPanel('rename')}>Rename workspace</button>}
          {selectedClub?.role === 'OWNER' && <button className="text-button" type="button" disabled={editingLocked}
            aria-pressed={panel === 'invite'} onClick={() => openPanel('invite')}>Invite organiser</button>}
          {selectedClub && <button className="text-button" type="button" disabled={editingLocked}
            aria-pressed={panel === 'members'} onClick={() => openPanel('members')}>Organisers and access</button>}
          <button className="text-button" type="button" disabled={switchingLocked || !loaded}
            aria-pressed={panel === 'create'} onClick={() => openPanel('create')}>Create club</button>
          <button className="text-button" type="button" disabled={switchingLocked || !loaded}
            aria-pressed={panel === 'join'} onClick={() => openPanel('join')}>Join a club</button>
        </div>
        {panel === 'rename' && (!clubId || selectedClub?.role === 'OWNER') && <form className="workspace-form" onSubmit={event => {
          event.preventDefault()
          void renamePersonal()
        }}>
          <label>Workspace name<input autoFocus required maxLength={100} value={editedName}
            disabled={editingLocked} onChange={event => setEditedName(event.target.value)} /></label>
          <div className="workspace-form-actions">
            <button type="submit" className="secondary" disabled={editingLocked || !editedName.trim() || editedName.trim() === (selectedClub?.name ?? personalName)}>
              {pending ? 'Saving name…' : 'Save name'}
            </button>
            <button type="button" className="text-button" disabled={pending} onClick={closePanel}>Cancel</button>
          </div>
        </form>}
        {panel === 'create' && <form className="workspace-form" onSubmit={event => { event.preventDefault(); void saveClub(false) }}>
          <p className="hint">Start a shared roster and invite other organisers to help.</p>
          <label>Club name<input autoFocus maxLength={100} required value={name} disabled={editingLocked}
            onChange={event => setName(event.target.value)} /></label>
          <div className="workspace-form-actions">
            <button className="secondary" type="submit" disabled={switchingLocked || !name.trim()}>{pending ? 'Creating club…' : 'Create club'}</button>
            <button className="text-button" type="button" disabled={pending} onClick={closePanel}>Cancel</button>
          </div>
        </form>}
        {panel === 'join' && <form className="workspace-form" onSubmit={event => {
          event.preventDefault()
          if (invitationMembership) void changeWorkspace(invitationMembership.id)
          else void (invitationPreview ? saveClub(true) : previewInvitation())
        }}>
          <label>Invitation link<input autoFocus required value={joinLink} disabled={editingLocked}
            onChange={event => { setJoinLink(event.target.value); setInvitationPreview(null); setError('') }} /></label>
          {invitationPreview ? <div className="border-y border-[#e3ebe5] py-3" role="status">
            <h3 className="text-base">{invitationPreview.name}</h3>
            <p className="hint">{invitationMembership ? 'You already belong to this club. Open its workspace to continue.' :
              'You’ll join as an organiser. You can edit shared players, ratings, position preferences, attendance, and guests.'}</p>
          </div> : <p className="hint">Check the club and access before joining.</p>}
          <div className="workspace-form-actions">
            <button className="secondary" type="submit" disabled={switchingLocked || !joinLink.trim()}>
              {pending ? 'Please wait…' : invitationMembership ? 'Open club workspace' : invitationPreview ? 'Join ' + invitationPreview.name : 'Review invitation'}
            </button>
            <button className="text-button" type="button" disabled={pending} onClick={closePanel}>Cancel</button>
          </div>
        </form>}
        {panel === 'members' && selectedClub && <ClubMembers key={selectedClub.id} clubId={selectedClub.id}
          clubName={selectedClub.name} csrfToken={csrfToken} owner={selectedClub.role === 'OWNER'} busy={editingLocked}
          onInvitationRevoked={() => { setInvite(''); setCopied(false) }} />}
        {panel === 'invite'  && selectedClub?.role === 'OWNER' && <div className="workspace-form">
          <p className="hint">Invite one organiser to edit {selectedClub.name}’s players and attendance. The link expires in seven days.</p>
          {invite ? <>
            <label>Invitation link<input readOnly value={invite} onFocus={event => event.currentTarget.select()} /></label>
            <div className="workspace-form-actions">
              <button className="secondary" type="button" onClick={() => void copyInvite()}>{copied ? 'Link copied' : 'Copy invitation link'}</button>
              <button className="text-button" type="button" disabled={pending} onClick={() => void createInvite()}>
                {pending ? 'Replacing link…' : 'Replace unused link'}
              </button>
            </div>
            <p className="hint">Replacing this link cancels the previous unused invitation.</p>
            {copied && <span className="sr-only" role="status">Invitation link copied.</span>}
          </> : <button type="button" className="secondary" disabled={editingLocked} onClick={() => void createInvite()}>
            {pending ? 'Creating link…' : 'Create invitation link'}
          </button>}
        </div>}
      </div>
    </details>
    {dirty && <p className="workspace-notice">Save your roster changes before switching, creating, or joining a workspace.</p>}
    {message && <p className="workspace-notice" role="status">{message}</p>}
    {error && <p className="error workspace-notice" role="alert">{error}</p>}
    {!loaded && !loading && <button className="text-button" type="button" onClick={() => void loadWorkspaces()}>Retry loading workspaces</button>}
  </section>
}
