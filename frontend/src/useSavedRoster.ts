import { useEffect, useState } from 'react'
import type { Player } from './types'

export interface RosterDraft {
  players: Player[]
  attendingIds: string[]
  guests: Player[]
}

interface SavedRoster { revision: number; draft: RosterDraft }
interface Session { enabled: boolean; name: string | null; csrfToken: string | null }

export function useSavedRoster() {
  const [session, setSession] = useState<Session>()
  const [saved, setSaved] = useState<SavedRoster>()
  const [draft, setDraft] = useState<RosterDraft>()
  const [busy, setBusy] = useState(false)
  const [message, setMessage] = useState('')
  const [conflict, setConflict] = useState(false)

  async function loadRoster() {
    setBusy(true)
    setMessage('')
    try {
      const response = await fetch('/api/roster')
      if (!response.ok) throw new Error()
      const roster: SavedRoster = await response.json()
      setSaved(roster)
      setDraft(roster.draft)
      setConflict(false)
    } catch {
      setMessage('Could not load your roster. Try again.')
    } finally {
      setBusy(false)
    }
  }

  useEffect(() => {
    const controller = new AbortController()
    async function loadSession() {
      try {
        const response = await fetch('/api/session', { signal: controller.signal })
        if (!response.ok) throw new Error()
        const account: Session = await response.json()
        setSession(account)
        if (account.name) await loadRoster()
      } catch {
        if (!controller.signal.aborted) setMessage('Could not check sign-in. Reload to try again.')
      }
    }
    void loadSession()
    return () => controller.abort()
  }, [])

  async function saveRoster() {
    if (!session?.csrfToken || !saved || !draft || busy || conflict) return
    setBusy(true)
    setMessage('')
    try {
      const response = await fetch('/api/roster', {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json', 'X-CSRF-TOKEN': session.csrfToken },
        body: JSON.stringify({ revision: saved.revision, draft }),
      })
      if (response.status === 409) {
        setConflict(true)
        setMessage('Your roster changed on another device. Load the latest version to continue.')
        return
      }
      if (!response.ok) {
        setMessage(response.status === 400 ? 'Check player names and the match selection.' :
          'Could not save your roster. If you signed out elsewhere, sign in again.')
        return
      }
      const { revision }: SavedRoster = await response.json()
      setSaved({ revision, draft })
    } catch {
      setMessage('Could not save your roster. Try again.')
    } finally {
      setBusy(false)
    }
  }

  const dirty = !!(saved && draft && JSON.stringify(saved.draft) !== JSON.stringify(draft))
  return { session, draft, setDraft, busy, dirty, message, conflict, loadRoster, saveRoster }
}
