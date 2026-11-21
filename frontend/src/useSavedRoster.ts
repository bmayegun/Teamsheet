import { useEffect, useRef, useState } from 'react'
import type { Player } from './types'

export interface RosterDraft {
  players: Player[]
  attendingIds: string[]
  guests: Player[]
}

interface SavedRoster { revision: number; draft: RosterDraft }
export interface Session { signInMethod?: string; enabled: boolean; name: string | null; csrfToken: string | null; emailSignInEnabled?: boolean; canLinkEmail?: boolean; linkedEmail?: string | null }

function rememberWorkspace(clubId: string | null) {
  try {
    if (clubId) sessionStorage.setItem('teamsheet-workspace', clubId)
    else sessionStorage.removeItem('teamsheet-workspace')
  } catch { /* Workspace switching also works with browser storage disabled. */ }
}

export function useSavedRoster() {
  const [clubId, setClubId] = useState<string | null>(() => {
    try { return sessionStorage.getItem('teamsheet-workspace') }
    catch { return null }
  })
  const [workspaceLoading, setWorkspaceLoading] = useState(false)
  const rosterUrl = clubId ? '/api/roster?clubId=' + encodeURIComponent(clubId) : '/api/roster'
  const [session, setSession] = useState<Session>()
  const [saved, setSaved] = useState<SavedRoster>()
  const [draft, setDraft] = useState<RosterDraft>()
  const [busy, setBusy] = useState(false)
  const [message, setMessage] = useState('')
  const [conflict, setConflict] = useState(false)
  const workspaceLocked = useRef(false)

  async function loadRoster() {
    setBusy(true)
    setMessage('')
    try {
      let response = await fetch(rosterUrl)
      if (clubId && (response.status === 403 || response.status === 400 || response.status === 404)) {
        response = await fetch('/api/roster')
        if (response.ok) {
          setClubId(null)
          rememberWorkspace(null)
          setMessage('That club is unavailable. Opened your personal workspace.')
        }
      }
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
      const response = await fetch(rosterUrl, {
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

  async function selectWorkspace(nextClubId: string | null): Promise<boolean> {
    if (workspaceLocked.current) return false
    workspaceLocked.current = true
    setWorkspaceLoading(true)
    setMessage('')
    try {
      const url = nextClubId ? '/api/roster?clubId=' + encodeURIComponent(nextClubId) : '/api/roster'
      const response = await fetch(url)
      if (!response.ok) throw new Error()
      const next: SavedRoster = await response.json()
      setClubId(nextClubId)
      rememberWorkspace(nextClubId)
      setSaved(next)
      setDraft(next.draft)
      setConflict(false)
      return true
    } catch {
      setMessage('Could not open that workspace. Try again.')
      return false
    } finally {
      setWorkspaceLoading(false)
    }
  }

  const dirty = !!(saved && draft && JSON.stringify(saved.draft) !== JSON.stringify(draft))
  workspaceLocked.current = busy || workspaceLoading || dirty
  return { session, draft, setDraft, busy: busy || workspaceLoading, dirty, message, conflict, loadRoster, saveRoster, clubId, selectWorkspace }
}
