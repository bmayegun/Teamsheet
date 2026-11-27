import { useRef, useState } from 'react'
import PlayerEditor from './PlayerEditor'
import PositionPicker from './PositionPicker'
import SkillRating from './SkillRating'
import type { Player } from './types'
import type { RosterDraft } from './useSavedRoster'

const nameKey = (name: string) => name.trim().replace(/\s+/g, ' ').toLowerCase()
const playerNameCollator = new Intl.Collator(undefined, { sensitivity: 'base', numeric: true })
const newPlayer = (name: string, skillRating = 3): Player =>
  ({ id: crypto.randomUUID(), name, skillRating, positions: [] })

interface Props {
  draft: RosterDraft
  onChange: (draft: RosterDraft) => void
  busy: boolean
  existingPlayers: Player[]
  onAdopt: () => void
}

export default function SavedPlayers({ draft, onChange, busy, existingPlayers, onAdopt }: Props) {
  const [rosterPaste, setRosterPaste] = useState('')
  const [matchPaste, setMatchPaste] = useState('')
  const [playerName, setPlayerName] = useState('')
  const [guestName, setGuestName] = useState('')
  const [selectedGuestIds, setSelectedGuestIds] = useState<string[] | null>(null)
  const [feedback, setFeedback] = useState('')
  const [search, setSearch] = useState('')
  const [selectedOnly, setSelectedOnly] = useState(false)
  const [matchUpdate, setMatchUpdate] = useState<{
    previous: RosterDraft
    next: RosterDraft
    message: string
  }>()
  const playingFilter = useRef<HTMLInputElement>(null)
  const pastePanel = useRef<HTMLDetailsElement>(null)
  const [matchPasteOpen, setMatchPasteOpen] = useState(false)
  const attendanceHeading = useRef<HTMLHeadingElement>(null)
  const [preview, setPreview] = useState<Player[] | null>(null)
  const [excludedPreviewIds, setExcludedPreviewIds] = useState<Set<string>>(() => new Set())
  const selectedPreview = preview?.filter(player => !excludedPreviewIds.has(player.id)) ?? []
  const allPreviewSelected = !!preview?.length && selectedPreview.length === preview.length
  const sortedSavedPlayers = [...draft.players].sort((a, b) => playerNameCollator.compare(a.name, b.name))
  const [choices, setChoices] = useState<Record<number, string>>({})
  const matchNames = (matchPasteOpen ? matchPaste : '').split(/\r?\n/).map(line => line.trim().replace(/^\d+[.)]\s+/, '')).filter(Boolean)
  const playersByName = new Map<string, Player[]>()
  for (const player of draft.players) {
    const key = nameKey(player.name)
    const matches = playersByName.get(key) ?? []
    matches.push(player)
    playersByName.set(key, matches)
  }
  const playersById = new Map(draft.players.map(player => [player.id, player]))
  const nameCounts = new Map<string, number>()
  for (const name of matchNames) {
    const key = nameKey(name)
    nameCounts.set(key, (nameCounts.get(key) ?? 0) + 1)
  }
  const matchRows = matchNames.map((name, index) => {
    const key = nameKey(name)
    return { name, index, repeated: (nameCounts.get(key) ?? 0) > 1, matches: playersByName.get(key) ?? [] }
  })
  const attending = new Set(draft.attendingIds)
  const matchNumbers = new Map(
    [...draft.players.filter(player => attending.has(player.id)), ...draft.guests]
      .map((player, index) => [player.id, index + 1] as const),
  )
  const playingCount = matchNumbers.size
  const visiblePlayers = sortedSavedPlayers.filter(player =>
    (!selectedOnly || attending.has(player.id)) && nameKey(player.name).includes(nameKey(search)),
  )
  const activeUpdate = matchUpdate?.next === draft ? matchUpdate : undefined

  function updatePreviewPlayer(id: string, changes: Partial<Pick<Player, 'skillRating' | 'positions'>>) {
    setPreview(current => current?.map(player => player.id === id ? { ...player, ...changes } : player) ?? null)
  }

  function previewRoster() {
    const additions: Player[] = []
    const used = new Set(draft.players.map(player => nameKey(player.name)))
    let skipped = 0
    for (const [index, line] of rosterPaste.split(/\r?\n/).entries()) {
      if (!line.trim()) continue
      const separator = line.includes('\t') ? '\t' : '|'
      const [name, rating, ...extra] = line.split(separator).map(cell => cell.trim())
      if (/^name$/i.test(name) && /^ratings?$/i.test(rating ?? '')) continue
      if (!name || name.length > 200 || extra.length || (rating && !/^[1-5]$/.test(rating))) {
        setPreview(null)
        setFeedback(`Check row ${index + 1}. Use a name and an optional rating from 1 to 5.`)
        return
      }
      if (used.has(nameKey(name))) {
        skipped++
        continue
      }
      used.add(nameKey(name))
      additions.push(newPlayer(name, rating ? Number(rating) : 3))
    }
    setExcludedPreviewIds(new Set())
    setPreview(additions)
    setFeedback(`${additions.length} new · ${skipped} matching or repeated names skipped`)
  }

  function selectionFor(row: typeof matchRows[number]) {
    if (row.matches.length === 1 && !row.repeated) return row.matches[0].id
    const choice = choices[row.index]
    return choice === 'skip' || playersById.has(choice) ? choice : 'guest'
  }

  const selectedIds = new Set<string>()
  const usedGuests = new Set<string>()
  const newGuestNames: string[] = []
  let matchError = ''
  let skippedRows = 0
  const reviewedRows = matchRows.map(row => {
    const choice = selectionFor(row)
    if (choice === 'guest') {
      const existing = draft.guests.find(guest => !usedGuests.has(guest.id) && nameKey(guest.name) === nameKey(row.name))
      if (existing) usedGuests.add(existing.id)
      else newGuestNames.push(row.name)
      return { ...row, choice, result: existing ? 'Keep existing guest' : 'Add as guest' }
    }
    if (choice === 'skip') {
      skippedRows++
      return { ...row, choice, result: 'Skip' }
    }
    const player = playersById.get(choice)!
    if (selectedIds.has(choice)) matchError = `${player.name} is selected more than once. Choose another player or skip a row.`
    selectedIds.add(choice)
    return { ...row, choice, result: `Matched to ${player.name}` }
  })
  const removedPlayers = draft.players.filter(player => attending.has(player.id) && !selectedIds.has(player.id))
  const nextCount = selectedIds.size + draft.guests.length + newGuestNames.length
  if (nextCount > 100) matchError = 'A match can have up to 100 players.'
  if (newGuestNames.some(name => name.length > 200)) matchError = 'Guest names can have up to 200 characters. Match or skip the long entry, or edit the pasted list.'

  function changeWithUndo(next: RosterDraft, message: string) {
    setMatchUpdate({ previous: draft, next, message })
    onChange(next)
    attendanceHeading.current?.focus()
  }

  function updateMatch(next: RosterDraft, message: string) {
    changeWithUndo(next, message)
    setSelectedGuestIds(null)
    setMatchPaste('')
    setChoices({})
    setSelectedOnly(next.attendingIds.length > 0 || next.guests.length > 0)
    setSearch('')
    if (pastePanel.current?.open) pastePanel.current.open = false
  }

  const selectedGuests = draft.guests.filter(guest => selectedGuestIds?.includes(guest.id))
  const selectedNames = selectedGuests.map(guest => nameKey(guest.name))
  const cannotSaveSelected = selectedGuests.some(guest => !guest.name.trim() || guest.name.trim().length > 200)
    ? 'Edit guests with missing or invalid names, or deselect them to save the others.'
    : selectedNames.some(name => playersByName.has(name)) || new Set(selectedNames).size !== selectedNames.length
      ? 'Review matching names individually, or deselect them to save the others.'
      : draft.players.length + selectedGuests.length > 1000
        ? 'Select fewer guests to stay within the 1,000-player roster limit.' : ''

  function promoteGuests(guests: Player[]) {
    const guestIds = new Set(guests.map(guest => guest.id))
    const players = guests.map(guest => ({ ...guest, name: guest.name.trim() }))
    changeWithUndo({
      players: [...draft.players, ...players],
      attendingIds: [...draft.attendingIds, ...guestIds],
      guests: draft.guests.filter(guest => !guestIds.has(guest.id)),
    }, `${players.length === 1 ? players[0].name : `${players.length} guests`} added to saved players. Select Save changes to sync.`)
  }

  function useExistingPlayer(guest: Player, player: Player) {
    if (attending.has(player.id)) return
    changeWithUndo({
      ...draft,
      attendingIds: [...draft.attendingIds, player.id],
      guests: draft.guests.filter(item => item.id !== guest.id),
    }, `Using saved player ${player.name} for this match.`)
  }

  function removeGuests(guestsToRemove: Player[]) {
    const ids = new Set(guestsToRemove.map(guest => guest.id))
    const guests = draft.guests.filter(guest => !ids.has(guest.id))
    const removed = draft.guests.length - guests.length
    setSelectedGuestIds(null)
    if (removed) changeWithUndo({ ...draft, guests }, `Removed ${removed} ${removed === 1 ? 'guest' : 'guests'}.`)
  }

  return <>
    <fieldset className="saved-roster-fields" disabled={busy} aria-labelledby="roster-heading">
      <div className="section-title">
        <h1 id="roster-heading" ref={attendanceHeading} tabIndex={-1}>Playing this match</h1>
        <span className="count" aria-live="polite">{playingCount} {playingCount === 1 ? 'player' : 'players'}</span>
      </div>
      <div className="match-tools">
        <details ref={pastePanel} onToggle={event => setMatchPasteOpen(event.currentTarget.open)}>
          <summary>Paste attendance</summary>
          {matchPasteOpen && <>
            <label className="add-form">Attendance list
              <textarea rows={4} placeholder="One name per line" value={matchPaste} onChange={event => {
                setMatchPaste(event.target.value)
                setChoices({})
              }} />
            </label>
            {reviewedRows.length > 0 && <>
              <p className="match-preview-total" aria-live="polite">
                <strong>{nextCount} playing after applying</strong>
                <span>{selectedIds.size} matched · {draft.guests.length} existing guests · {newGuestNames.length} new guests · {skippedRows} skipped</span>
              </p>
              {removedPlayers.length > 0 && <p className="hint">Will deselect {removedPlayers.map(player => player.name).join(', ')}.</p>}
              <ul className="attendance-review">
                {reviewedRows.map(row => <li key={row.index}>
                  <strong>{row.name}</strong>
                  {row.matches.length === 1 && !row.repeated ? <span>{row.result}</span> : <label>
                    <span>{row.repeated ? 'Repeated name' : row.matches.length > 1 ? 'Multiple matches' : 'No saved match'}</span>
                    <select value={row.choice} aria-label={`Action for ${row.name}, entry ${row.index + 1}`}
                      onChange={event => setChoices({ ...choices, [row.index]: event.target.value })}>
                      <option value="guest">{row.choice === 'guest' ? row.result : 'Add as guest'}</option>
                      {(row.matches.length ? row.matches : draft.players).map(player => <option key={player.id} value={player.id}>
                        {player.name} · {player.skillRating}/5{player.positions.length ? ' · ' + player.positions.join(', ') : ''}
                      </option>)}
                      <option value="skip">Skip</option>
                    </select>
                  </label>}
                </li>)}
              </ul>
              {matchError && <p className="error" role="alert">{matchError}</p>}
              <button className="secondary" type="button" disabled={!!matchError} onClick={() => {
                updateMatch({
                  ...draft,
                  attendingIds: [...selectedIds],
                  guests: [...draft.guests, ...newGuestNames.map(name => newPlayer(name))],
                }, 'Attendance list applied.')
              }}>Apply attendance</button>
            </>}
          </>}
        </details>
        <details>
          <summary>Add guest</summary>
          <form className="add-form inline-form" onSubmit={event => {
            event.preventDefault()
            if (!guestName.trim() || playingCount >= 100) return
            onChange({ ...draft, guests: [...draft.guests, newPlayer(guestName.trim())] })
            setGuestName('')
          }}>
            <label>Guest name<input value={guestName} onChange={event => setGuestName(event.target.value)} maxLength={200} /></label>
            <button type="submit" className="secondary" disabled={!guestName.trim() || playingCount >= 100}>Add to match</button>
          </form>
        </details>
      </div>

      {activeUpdate && <div className="undo-status">
        <span role="status">{activeUpdate.message}</span>
        <button className="text-button" type="button" onClick={() => {
          onChange(activeUpdate.previous)
          setMatchUpdate(undefined)
          setSelectedOnly(false)
          attendanceHeading.current?.focus()
        }}>Undo</button>
      </div>}

      <details className="roster-management">
        <summary>Manage saved players <span className="count">{draft.players.length}</span></summary>
        <fieldset className="saved-roster-fields" disabled={busy} aria-label="Manage saved players">
          {existingPlayers.length > 0 && <button type="button" className="secondary" onClick={onAdopt}>Add players from this browser</button>}
          <form className="add-form" onSubmit={event => {
            event.preventDefault()
            const name = playerName.trim()
            if (!name || draft.players.length >= 1000) return
            onChange({ ...draft, players: [...draft.players, newPlayer(name)] })
            setPlayerName('')
          }}>
            <label>Player name<input value={playerName} onChange={event => setPlayerName(event.target.value)} maxLength={200} /></label>
            <button type="submit" className="secondary" disabled={!playerName.trim() || draft.players.length >= 1000}>Add saved player</button>
          </form>
          <details>
            <summary>Add players in bulk</summary>
            <label className="add-form">Player list
              <textarea rows={3} placeholder="One name per line" value={rosterPaste}
                aria-describedby="player-import-hint"
                onChange={event => { setRosterPaste(event.target.value); setPreview(null); setFeedback('') }} />
            </label>
            <p className="hint" id="player-import-hint">Type Alex | 4 to set a rating, or paste name and rating columns. Names alone start at 3.</p>
            <button className="secondary" type="button" onClick={previewRoster} disabled={!rosterPaste.trim() || preview !== null}>Preview players</button>
            {preview && preview.length > 0 && <>
              <p className="hint">Select positions in preference order. Leave them blank if you’re unsure.</p>
              <div className="section-title">
                <label className="attending-filter">
                  <input type="checkbox" checked={allPreviewSelected}
                    ref={input => { if (input) input.indeterminate = !allPreviewSelected && selectedPreview.length > 0 }}
                    onChange={event => setExcludedPreviewIds(new Set(event.target.checked ? [] : preview.map(player => player.id)))} />
                  Select all
                </label>
                <span className="hint" role="status">{selectedPreview.length} of {preview.length} selected</span>
              </div>
              <ul className="player-list" aria-label="Players to add">{preview.map(player => <li key={player.id} className="bulk-preview-row">
                <label>
                  <input type="checkbox" checked={!excludedPreviewIds.has(player.id)} onChange={event => {
                    const checked = event.target.checked
                    setExcludedPreviewIds(current => {
                      const next = new Set(current)
                      if (checked) next.delete(player.id)
                      else next.add(player.id)
                      return next
                    })
                  }} />
                  <span>{player.name}</span>
                </label>
                <div className="bulk-preview-controls">
                  <SkillRating playerName={player.name} value={player.skillRating}
                    onChange={skillRating => updatePreviewPlayer(player.id, { skillRating })} />
                  <PositionPicker playerName={player.name} value={player.positions}
                    onChange={positions => updatePreviewPlayer(player.id, { positions })} />
                </div>
              </li>)}</ul>
              <button type="button" className="secondary" disabled={selectedPreview.length === 0} onClick={() => {
                const existing = new Set(draft.players.map(player => nameKey(player.name)))
                const additions = selectedPreview.filter(player => !existing.has(nameKey(player.name)))
                if (draft.players.length + additions.length > 1000) {
                  setFeedback('A roster can have up to 1,000 saved players. Select fewer players to add.')
                  return
                }
                onChange({ ...draft, players: [...draft.players, ...additions] })
                setPreview(null)
                setRosterPaste('')
                setFeedback('')
              }}>Add to saved players</button>
            </>}
          </details>
          {feedback && <p role="status">{feedback}</p>}
          {draft.players.length > 0 && <>
            <p className="hint">Open a player to edit their rating and positions. Select positions in preference order.</p>
            <ul className="player-list">
              {sortedSavedPlayers.map(player => <PlayerEditor key={player.id} player={player} collapsed
                onChange={updated => onChange({ ...draft, players: draft.players.map(item => item.id === updated.id ? updated : item) })}
                onRemove={() => onChange({ ...draft, players: draft.players.filter(item => item.id !== player.id),
                  attendingIds: draft.attendingIds.filter(id => id !== player.id) })} />)}
            </ul>
          </>}
        </fieldset>
      </details>

      {draft.players.length > 0 ? <>
        <div className="attendance-filters">
          <label>Find a saved player
            <input type="search" value={search} onChange={event => setSearch(event.target.value)} />
          </label>
          <label className="attending-filter">
            <input ref={playingFilter} type="checkbox" checked={selectedOnly} onChange={event => setSelectedOnly(event.target.checked)} />
            Playing only
          </label>
        </div>
        {visiblePlayers.length ? <ul className="attendance-list" aria-label="Saved players available for this match">
          {visiblePlayers.map(player => <li key={player.id}>
            <label className="attendance-row" data-selected={attending.has(player.id)}>
              <input type="checkbox" checked={attending.has(player.id)} disabled={playingCount >= 100 && !attending.has(player.id)}
                onChange={event => {
                  const checked = event.target.checked
                  onChange({ ...draft, attendingIds: checked ?
                    [...draft.attendingIds, player.id] : draft.attendingIds.filter(id => id !== player.id) })
                  if (!checked && selectedOnly) playingFilter.current?.focus()
                }} />
              <span>{player.name || 'Unnamed player'}</span>
              {attending.has(player.id) && <span className="match-number" aria-label={`Match participant ${matchNumbers.get(player.id)}`}>{matchNumbers.get(player.id)}</span>}
            </label>
          </li>)}
        </ul> : <p className="hint">{search.trim() ? 'No saved players match your search.' : 'No saved players selected.'}</p>}
      </> : <p className="hint">Paste attendance to start with guests, or add reusable players under Manage saved players.</p>}

      {draft.guests.length > 0 && <div className="guest-list">
        <div className="section-title">
          <h2>Guests</h2>
          <button type="button" className="text-button" onClick={() => setSelectedGuestIds(selectedGuestIds ? null : [])}>
            {selectedGuestIds ? 'Cancel selection' : 'Select guests'}
          </button>
        </div>
        {selectedGuestIds ? <>
          <ul className="player-list" aria-label="Guests to remove">
            {draft.guests.map(guest => {
              const checked = selectedGuestIds.includes(guest.id)
              return <li key={guest.id}>
                <label className="attendance-row" data-selected={checked}>
                  <input type="checkbox" checked={checked} onChange={event => {
                    setSelectedGuestIds(event.target.checked ? [...selectedGuestIds, guest.id] : selectedGuestIds.filter(id => id !== guest.id))
                  }} />
                  <span>{guest.name || 'Unnamed guest'}</span>
                  <span className="match-number" aria-label={`Match participant ${matchNumbers.get(guest.id)}`}>{matchNumbers.get(guest.id)}</span>
                </label>
              </li>
            })}
          </ul>
          <div className="mt-3 flex flex-wrap gap-2">
            <button type="button" className="secondary" disabled={!selectedGuests.length || !!cannotSaveSelected} onClick={() => {
              promoteGuests(selectedGuests)
              setSelectedGuestIds(null)
            }}>Save selected{selectedGuests.length > 0 && ` (${selectedGuests.length})`}</button>
            <button type="button" className="remove" disabled={!selectedGuests.length}
              onClick={() => removeGuests(selectedGuests)}>Remove selected{selectedGuests.length > 0 && ` (${selectedGuests.length})`}</button>
          </div>
          {selectedGuests.length > 0 && cannotSaveSelected && <p className="hint" role="status">{cannotSaveSelected}</p>}
        </> : <ul className="player-list">
          {draft.guests.map(guest => {
            const name = guest.name.trim()
            const matches = name ? playersByName.get(nameKey(name)) ?? [] : []
            const addError = !name || name.length > 200 ? 'Enter a name between 1 and 200 characters.' :
              draft.players.length >= 1000 ? 'The saved roster has reached its 1,000-player limit.' : ''
            return <PlayerEditor key={guest.id} player={guest} matchNumber={matchNumbers.get(guest.id)} collapsed removeOnRow
              onChange={updated => onChange({ ...draft, guests: draft.guests.map(item => item.id === updated.id ? updated : item) })}
              onRemove={() => removeGuests([guest])}>
              <div className="mt-3">
                {matches.length > 0 ? <details key={nameKey(name)}>
                  <summary className="text-button cursor-pointer py-3">Save player</summary>
                  <p className="hint">Use a saved player’s rating and positions, or keep this guest’s details in a separate record.</p>
                  <ul className="player-list">
                    {matches.map((player, index) => <li className="flex flex-wrap items-center justify-between gap-2 py-2" key={player.id}>
                      <span className="min-w-0 text-sm wrap-anywhere">
                        {matches.length > 1 && `${index + 1}. `}<strong>{player.name}</strong>
                        {' · '}{player.skillRating}/5 · {player.positions.join(' → ') || 'No preferred positions'}
                      </span>
                      {attending.has(player.id) ? <span className="hint">Already playing</span> :
                        <button type="button" className="secondary" onClick={() => useExistingPlayer(guest, player)}
                          aria-label={matches.length > 1 ? `Use existing player ${index + 1}: ${player.name}` : undefined}>
                          Use existing player
                        </button>}
                    </li>)}
                  </ul>
                  <button type="button" className="secondary mt-2" disabled={!!addError}
                    onClick={() => promoteGuests([guest])}>Add as a separate player</button>
                </details> : <button type="button" className="secondary" disabled={!!addError}
                  aria-label={name ? `Save player: ${name}` : undefined}
                  onClick={() => promoteGuests([guest])}>Save player</button>}
                {addError && <p className="hint">{addError}</p>}
              </div>
            </PlayerEditor>
          })}
        </ul>}
      </div>}
      {playingCount >= 100 && <p className="hint">The match has reached the 100-player limit.</p>}
      <div className="match-reset">
        {playingCount > 0 && <button className="text-button" type="button" onClick={() => {
          updateMatch({ ...draft, attendingIds: [], guests: [] }, 'Attendance and guests cleared.')
        }}>Clear attendance and guests</button>}
      </div>
    </fieldset>


  </>
}
