import { useRef, useState, type FormEvent } from 'react'
import PlayerEditor from './PlayerEditor'
import TeamResults from './TeamResults'
import ShareDraw from './ShareDraw'
import { generateTeams } from './teamService'
import { positions, positionNames, presets, type Mode, type Player, type Targets, type Team } from './types'

const modes: { value: Mode; name: string; description: string }[] = [
  { value: 'RANDOM', name: 'Random', description: 'Shuffle the roster into even teams.' },
  { value: 'SKILL', name: 'Balanced by Skill', description: 'Keep team average ratings close.' },
  { value: 'SKILL_AND_POSITION', name: 'Balanced by Skill + Position', description: 'Balance ratings and spread position coverage.' },
]

export default function App() {
  const [players, setPlayers] = useState<Player[]>([])
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
    if (pending) return
    setError('')
    if (players.length < teamCount || !players.length) {
      setError('Add enough players for every team to have at least one.')
      return
    }
    if (players.some(player => !player.name.trim())) {
      setError('Enter a name for every player.')
      return
    }
    if (targetTotal !== teamSize) {
      setError('Position targets must add up to the selected team size.')
      return
    }
    const controller = new AbortController()
    activeRequest.current = controller
    setPending(true)
    try {
      const teams = await generateTeams({
        players,
        numberOfTeams: teamCount,
        balancingMode: mode,
        gameFormat: { name: format, playersPerTeam: teamSize, positionTargets: targets },
      }, controller.signal)
      if (controller.signal.aborted) return
      setResult({ teams, targets })
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

  return <>
    <header className="masthead">
      <div className="page-width brand"><span className="brand-icon" aria-hidden="true">▥</span>TeamSheet</div>
    </header>
    <main className="page-width">
      <div className="intro">
        <h1>Build balanced teams.</h1>
        <p>Add your players, choose a game format, and generate teams for your next match.</p>
      </div>
      <div className="workspace">
        <section className="card" aria-labelledby="roster-heading">
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
        </section>
        <form className="card settings" onSubmit={generate} aria-labelledby="settings-heading">
          <h2 id="settings-heading">Game settings</h2>
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
          <fieldset>
            <legend>Position targets <span className="hint">{targetTotal}/{teamSize}</span></legend>
            <div className="target-fields">
              {positions.map(position => <label key={position} title={positionNames[position]}>{position}
                <input required aria-label={positionNames[position] + ' target'} type="number" min={0} max={100} value={targets[position]}
                  onChange={event => { clearResult(); setTargets({ ...targets, [position]: Number(event.target.value) }) }} />
              </label>)}
            </div>
            <p className="hint">Targets guide position coverage. Everyone plays, including extras.</p>
          </fieldset>
          <fieldset>
            <legend>Generation mode</legend>
            <div className="mode-list">
              {modes.map(option => <label className="mode" key={option.value}>
                <input type="radio" name="mode" value={option.value} checked={mode === option.value}
                  onChange={() => { clearResult(); setMode(option.value) }} />
                <span><strong>{option.name}</strong><small>{option.description}</small></span>
              </label>)}
            </div>
          </fieldset>
          {error && <p className="error" role="alert">{error}</p>}
          <button className="primary" type="submit" disabled={pending}>
            {pending ? 'Generating…' : result ? 'Regenerate teams' : 'Generate teams'}
          </button>
        </form>
      </div>
      {result && <section className="results" aria-labelledby="results-heading">
        <h2 id="results-heading" tabIndex={-1} ref={resultsHeading}>Generated teams</h2>
        <TeamResults teams={result.teams} targets={result.targets} />
        <ShareDraw teams={result.teams} targets={result.targets} />
      </section>}
    </main>
  </>
}
