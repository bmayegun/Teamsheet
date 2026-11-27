import AccountMenu from './AccountMenu'
import ClubWorkspaces from './ClubWorkspaces'
import PasswordSignIn from './PasswordSignIn'
import { useRef, useState, type FormEvent } from 'react'
import PlayerEditor from './PlayerEditor'
import ShareDraw from './ShareDraw'
import SavedPlayers from './SavedPlayers'
import { useSavedRoster } from './useSavedRoster'
import { generateTeams } from './teamService'
import { positions, positionNames, presets, type Mode, type Player, type Targets, type Team } from './types'

const modes: { value: Mode; name: string; description: string }[] = [
  { value: 'RANDOM', name: 'Random', description: 'Shuffle the roster into even teams.' },
  { value: 'SKILL', name: 'Balanced by Skill', description: 'Keep team average ratings close.' },
  { value: 'SKILL_AND_POSITION', name: 'Balanced by Skill + Position', description: 'Balance ratings and spread position coverage.' },
]

export default function App() {
  const [players, setPlayers] = useState<Player[]>(() => {
    try { return JSON.parse(sessionStorage.getItem('teamsheet-guest-roster') ?? '[]') }
    catch { return [] }
  })
  const roster = useSavedRoster()
  const [hideSignInMethods, setHideSignInMethods] = useState(false)
  const [signInTitle, setSignInTitle] = useState('Sign in to TeamSheet')
  const signInDialog = useRef<HTMLDialogElement>(null)
  const matchPlayers = roster.draft ? [
    ...roster.draft.players.filter(player => roster.draft?.attendingIds.includes(player.id)),
    ...roster.draft.guests,
  ] : players
  const [names, setNames] = useState('')
  const [format, setFormat] = useState('5-a-side')
  const [teamSize, setTeamSize] = useState(5)
  const [targets, setTargets] = useState<Targets>(presets['5-a-side'])
  const [teamCount, setTeamCount] = useState(2)
  const [mode, setMode] = useState<Mode>('SKILL_AND_POSITION')
  const [result, setResult] = useState<{ teams: Team[]; targets: Targets }>()
  const [pending, setPending] = useState(false)
  const [error, setError] = useState('')
  const [entryError, setEntryError] = useState('')
  const activeRequest = useRef<AbortController | null>(null)
  const resultsHeading = useRef<HTMLHeadingElement>(null)
  const targetTotal = Object.values(targets).reduce((sum, value) => sum + value, 0)
  const positionMode = mode === 'SKILL_AND_POSITION'
  const invalidTarget = Object.values(targets).some(value => !Number.isInteger(value) || value < 0)
  const targetMismatch = positionMode && (invalidTarget || targetTotal !== teamSize)
  const targetMessage = invalidTarget ? 'Use whole numbers of zero or more for position targets.' :
    `Position targets add up to ${targetTotal}. Set them to ${teamSize} for this format.`
  const shortRoster = matchPlayers.length < teamCount || !matchPlayers.length

  function clearResult() {
    activeRequest.current?.abort()
    activeRequest.current = null
    setPending(false)
    setResult(undefined)
    setError('')
  }

  function addPlayers(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const entries = names.split(/[\n,]/).map(name => name.trim()).filter(Boolean)
    if (!entries.length) return
    if (players.length + entries.length > 100) {
      setEntryError('Enter at most 100 players.')
      return
    }
    clearResult()
    setPlayers([...players, ...entries.map(name => ({ id: crypto.randomUUID(), name, skillRating: 3, positions: [] }))])
    setNames('')
    setEntryError('')
  }

  function changeFormat(value: string) {
    clearResult()
    setFormat(value)
    const preset = presets[value]
    if (preset) {
      setTargets(preset)
      setTeamSize(Object.values(preset).reduce((sum, count) => sum + count, 0))
    }
  }

  async function generate(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (pending || (roster.session?.name && (!roster.draft || roster.busy))) return
    setError('')
    if (shortRoster) {
      setError('Add enough players for every team to have at least one.')
      return
    }
    if (matchPlayers.some(player => !player.name.trim())) {
      setError('Enter a name for every player.')
      return
    }
    if (targetMismatch) {
      setError(targetMessage)
      return
    }
    const drawTargets = positionMode ? targets : presets[format] ?? { GK: 0, DEF: 0, MID: 0, ATT: teamSize }
    const controller = new AbortController()
    setResult(undefined)
    activeRequest.current = controller
    setPending(true)
    try {
      const teams = await generateTeams({
        players: matchPlayers,
        numberOfTeams: teamCount,
        balancingMode: mode,
        gameFormat: { name: format, playersPerTeam: teamSize, positionTargets: drawTargets },
      }, controller.signal)
      if (controller.signal.aborted) return
      setResult({ teams, targets: drawTargets })
      requestAnimationFrame(() => resultsHeading.current?.focus())
    } catch (cause) {
      if (!controller.signal.aborted) setError(cause instanceof Error ? cause.message : 'Unable to generate teams.')
    } finally {
      if (activeRequest.current === controller) {
        activeRequest.current = null
        setPending(false)
      }
    }
  }

  function rememberGuestRoster() {
    try {
      sessionStorage.setItem('teamsheet-guest-roster', JSON.stringify(players))
      const invitation = new URLSearchParams(location.hash.slice(1)).get('club-invite')
      if (invitation) sessionStorage.setItem('teamsheet-club-invite', invitation)
    }
    catch { /* Storage can be disabled in the browser. */ }
  }


  return <>
    <header className="masthead">
      <div className="page-width brand">
        <span className="brand-icon" aria-hidden="true">▥</span>TeamSheet
        {roster.session?.enabled && <div className="account">
          {roster.session.name ? <AccountMenu session={roster.session} /> : <button type="button" aria-haspopup="dialog" aria-controls="sign-in-panel"
            onClick={() => signInDialog.current?.showModal()}>Sign in</button>}
        </div>}
      </div>
    </header>
    <main className={roster.draft && !result ? "page-width has-match-actions" : "page-width"}>
      {!roster.session?.name && <div className="intro">
        <h1>Build balanced teams.</h1>
        <p>Add your players, choose a game format, and generate teams for your next match.</p>
      </div>}
      {roster.session?.enabled && !roster.session.name && <dialog ref={signInDialog}
        id="sign-in-panel" className="sign-in-dialog" aria-labelledby="sign-in-title">
        <div className="flex items-start justify-between gap-4">
          <h2 id="sign-in-title">{signInTitle}</h2>
          <button type="button" aria-label="Close sign-in" className="sign-in-close"
            onClick={() => signInDialog.current?.close()}>
            <svg width="20" height="20" viewBox="0 0 20 20" aria-hidden="true">
              <path d="m5 5 10 10M15 5 5 15" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" />
            </svg>
          </button>
        </div>
        {!hideSignInMethods && <><p className="hint">Open your saved players and club workspaces.</p>
        <a className="sign-in-google" href="/oauth2/authorization/google"
          onClick={rememberGuestRoster}>
            <img src="/brand/google-signin.svg" width="20" height="20" alt="" />
            Continue with Google
          </a></>}
        {roster.session.csrfToken && <>
          {!hideSignInMethods && <div className="sign-in-divider">or use your email</div>}
          <PasswordSignIn csrfToken={roster.session.csrfToken} emailEnabled={!!roster.session.emailSignInEnabled}
            onStage={(title, hide) => { setSignInTitle(title); setHideSignInMethods(hide) }}
            onGoogleSignIn={rememberGuestRoster} onDone={() => { rememberGuestRoster(); location.assign('/') }} />
        </>}
      </dialog>}
      {roster.draft && roster.session?.csrfToken && <ClubWorkspaces
        csrfToken={roster.session.csrfToken} clubId={roster.clubId} dirty={roster.dirty} busy={roster.busy}
        onSelect={async clubId => {
          const changed = await roster.selectWorkspace(clubId)
          if (changed) clearResult()
          return changed
        }} />}
      {new URLSearchParams(location.search).has('signInFailed') && <p className="error" role="alert">Google sign-in failed. Try again.</p>}
      {!roster.session && roster.message && <p className="error" role="alert">{roster.message}</p>}
      <div className={roster.session?.name ? "workspace match-workspace" : "workspace"}>
        <section className="card" aria-labelledby="roster-heading">
          {roster.draft ? <SavedPlayers key={roster.clubId ?? 'personal'} draft={roster.draft} busy={roster.busy} existingPlayers={players}
            onChange={draft => { clearResult(); roster.setDraft(draft) }}
            onAdopt={() => {
              const draft = roster.draft!
              const existing = new Set(draft.players.map(player => player.id))
              const additions = players.filter(player => !existing.has(player.id)).slice(0, 1000 - draft.players.length)
              if (additions.length) {
                clearResult()
                roster.setDraft({ ...draft, players: [...draft.players, ...additions] })
              }
            }} /> : roster.session?.name ? <>
            <h1 id="roster-heading">Playing this match</h1>
            <p role="status">{roster.message || 'Loading roster…'}</p>
            {!roster.busy && roster.message && <button className="secondary" type="button" onClick={roster.loadRoster}>Retry</button>}
          </> : <>
          <div className="section-title"><h2 id="roster-heading">Player roster</h2><span className="count">{players.length} players</span></div>
          <form onSubmit={addPlayers} className="add-form">
            <label htmlFor="names">Player names</label>
            <textarea id="names" rows={3} value={names} onChange={event => { setNames(event.target.value); setEntryError('') }}
              placeholder={'Enter one name per line or separate names with commas'} />
            <button type="submit" className="secondary" disabled={!names.trim()}>Add players</button>
            {entryError && <p className="error" role="alert">{entryError}</p>}
          </form>
          {players.length > 0 && <>
            <p className="hint">Select positions in preference order. Leave them blank if unknown.</p>
            <ul className="player-list">
              {players.map(player => <PlayerEditor key={player.id} player={player}
                onChange={updated => { clearResult(); setPlayers(players.map(item => item.id === updated.id ? updated : item)) }}
                onRemove={() => { clearResult(); setEntryError(''); setPlayers(players.filter(item => item.id !== player.id)) }} />)}
            </ul>
          </>}
          </>}
        </section>
        <form className="card settings" onSubmit={generate} aria-labelledby="settings-heading">
          <h2 id="settings-heading" tabIndex={-1}>Game settings</h2>
          <div className="settings-fields">
            <label>Game format
              <select value={format} onChange={event => changeFormat(event.target.value)}>
                {Object.keys(presets).map(preset => <option key={preset}>{preset}</option>)}<option>Custom</option>
              </select>
            </label>
            <label>Number of teams
              <input required type="number" min={1} max={100} value={teamCount || ''} onChange={event => {
                clearResult()
                setTeamCount(Number(event.target.value))
              }} />
            </label>
            {format === 'Custom' && <label>Players per team
              <input required type="number" min={1} max={100} value={teamSize || ''} onChange={event => {
                clearResult()
                setTeamSize(Number(event.target.value))
              }} />
            </label>}
          </div>
          <label className="mode-field">Balancing mode
            <select value={mode} aria-describedby="mode-description" onChange={event => {
              clearResult()
              setMode(event.target.value as Mode)
            }}>
              {modes.map(option => <option key={option.value} value={option.value}>{option.name}</option>)}
            </select>
            <span className="hint" id="mode-description">{modes.find(option => option.value === mode)?.description}</span>
          </label>
          {positionMode && <div>
            <details className="target-settings">
              <summary>Edit position targets{targetMismatch && ' (check total)'}</summary>
              <fieldset>
                <legend className="sr-only">Position targets</legend>
                <div className="target-fields">
                  {positions.map(position => <label key={position} title={positionNames[position]}>{position}
                    <input aria-label={positionNames[position] + ' target'} aria-describedby="position-total"
                      type="number" value={targets[position]}
                      onChange={event => { clearResult(); setTargets({ ...targets, [position]: Number(event.target.value) }) }} />
                  </label>)}
                </div>
                <p className="hint">Targets guide position coverage.</p>
              </fieldset>
            </details>
            <p className={targetMismatch ? 'error mt-2' : 'sr-only'} id="position-total" aria-live="polite">
              {targetMismatch ? targetMessage : `${targetTotal} of ${teamSize} positions assigned.`}
            </p>
          </div>}
          {roster.draft && <div className="roster-save" aria-label="Roster sync">
            <p className="hint" role="status">
              {roster.message || (roster.busy ? 'Saving…' : roster.dirty ? 'Unsaved changes' : 'Saved to your account')}
            </p>
            {roster.conflict && <button type="button" className="text-button" disabled={roster.busy}
              onClick={() => { clearResult(); void roster.loadRoster() }}>Discard local edits and load latest</button>}
          </div>}
          <div className={roster.draft && !result ? 'draw-actions mobile-draw-actions' : 'draw-actions'}>
            {roster.draft && !result && <div className="match-action-summary">
              <p className="match-action-count">
                {matchPlayers.length} playing <span aria-hidden="true">·</span> {teamCount || 0} {teamCount === 1 ? 'team' : 'teams'}
              </p>
              <a className="settings-link text-button" href="#settings-heading">Game settings</a>
            </div>}
            {error && <p className="error" role="alert">{error}</p>}
            <div className="draw-buttons">
              {roster.draft && roster.dirty && <button type="button" className="secondary"
                disabled={roster.busy || roster.conflict} onClick={roster.saveRoster}>Save changes</button>}
              <button className="primary" type="submit" disabled={pending || (Boolean(roster.session?.name) && (!roster.draft || roster.busy))}>
                {pending ? 'Generating…' : result ? 'Regenerate teams' : 'Generate teams'}
              </button>
            </div>
            {roster.draft && roster.dirty && !error && !result && <p className="match-action-state">
              {roster.message || (roster.busy ? 'Saving to your account…' : 'Unsaved changes')}
            </p>}
          </div>
        </form>
      </div>
      {result && <ShareDraw teams={result.teams} targets={result.targets} headingRef={resultsHeading} />}
    </main>
  </>
}
