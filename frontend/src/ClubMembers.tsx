import { useEffect, useState } from 'react'

interface Member { id: string; name: string; role: 'OWNER' | 'ORGANISER'; isYou: boolean }
interface Management { members: Member[]; invitationExpiresAt: string | null }

export default function ClubMembers({ clubId, clubName, csrfToken, owner, busy, onInvitationRevoked }: {
  clubId: string
  clubName: string
  csrfToken: string
  owner: boolean
  busy: boolean
  onInvitationRevoked: () => void
}) {
  const [management, setManagement] = useState<Management>()
  const [pending, setPending] = useState(false)
  const [error, setError] = useState('')
  const [message, setMessage] = useState('')
  const [removing, setRemoving] = useState<string | null>(null)

  async function load(signal?: AbortSignal) {
    setPending(true)
    setError('')
    try {
      const response = await fetch('/api/clubs/' + clubId + '/members', { signal })
      if (!response.ok) throw new Error()
      const result: Management = await response.json()
      if (!signal?.aborted) setManagement(result)
    } catch {
      if (!signal?.aborted) setError('Could not load organisers. Retry, or ask the owner to check your access.')
    } finally {
      if (!signal?.aborted) setPending(false)
    }
  }

  useEffect(() => {
    const controller = new AbortController()
    void load(controller.signal)
    return () => controller.abort()
  }, [clubId])

  async function removeAccess(member: Member) {
    setPending(true)
    setError('')
    setMessage('')
    try {
      const response = await fetch('/api/clubs/' + clubId + '/members/' + member.id, {
        method: 'DELETE', headers: { 'X-CSRF-TOKEN': csrfToken },
      })
      if (!response.ok) throw new Error(response.status === 401 || response.status === 403
        ? 'Your session expired or your access changed. Reload the page and try again.' : 'Could not remove access. Refresh the organisers list and try again.')
      setManagement(current => current && { ...current, members: current.members.filter(item => item.id !== member.id) })
      setRemoving(null)
      setMessage(`Access removed for ${member.name}.`)
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : 'Could not remove access. Try again.')
    } finally {
      setPending(false)
    }
  }

  async function revoke() {
    setPending(true)
    setError('')
    setMessage('')
    try {
      const response = await fetch('/api/clubs/' + clubId + '/invitations', {
        method: 'DELETE', headers: { 'X-CSRF-TOKEN': csrfToken },
      })
      if (!response.ok) throw new Error(response.status === 401 || response.status === 403
        ? 'Your session expired or your access changed. Reload the page and try again.' : 'Could not revoke the invitation. Try again.')
      setManagement(current => current && { ...current, invitationExpiresAt: null })
      onInvitationRevoked()
      setMessage('Unused invitation revoked.')
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : 'Could not revoke the invitation. Try again.')
    } finally {
      setPending(false)
    }
  }

  const locked = busy || pending
  return <div className="workspace-form" aria-busy={pending}>
    <h3 className="text-base">Organisers of {clubName}</h3>
    {!management && pending && <p className="hint" role="status">Loading organisers…</p>}
    {management && <ul className="divide-y divide-[#e3ebe5]">
      {management.members.map(member => <li className="py-3" key={member.id}>
        <div className="flex flex-wrap items-center justify-between gap-2">
          <div className="min-w-0 text-sm wrap-anywhere">
            <strong>{member.name}{member.isYou && ' (you)'}</strong>
            <span className="block text-[#4c6259]">{member.role === 'OWNER' ? 'Owner' : 'Organiser'}</span>
          </div>
          {owner && member.role === 'ORGANISER' && <button type="button" className="text-button" disabled={locked}
            onClick={() => { setRemoving(member.id); setError(''); setMessage('') }}>Remove access</button>}
        </div>
        {removing === member.id && <div className="mt-2">
          <p className="hint">Remove {member.name} from {clubName}? They will need a new invitation to rejoin.</p>
          <div className="workspace-form-actions">
            <button type="button" className="secondary" disabled={locked} onClick={() => void removeAccess(member)}>
              {pending ? 'Removing…' : 'Confirm removal'}
            </button>
            <button type="button" className="text-button" disabled={locked} onClick={() => setRemoving(null)}>Keep access</button>
          </div>
        </div>}
      </li>)}
    </ul>}
    {owner && management?.invitationExpiresAt && <div>
      <p className="hint">An unused invitation expires {new Date(management.invitationExpiresAt).toLocaleDateString()}.</p>
      <button type="button" className="text-button" disabled={locked} onClick={() => void revoke()}>Revoke unused invitation</button>
    </div>}
    {error && <p className="error" role="alert">{error}</p>}
    {message && <p className="hint" role="status">{message}</p>}
    <button type="button" className="text-button" disabled={locked} onClick={() => void load()}>Refresh organisers</button>
  </div>
}
