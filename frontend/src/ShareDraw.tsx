import { useState, type Ref } from 'react'
import TeamResults from './TeamResults'
import { positions, type Targets, type Team } from './types'

export default function ShareDraw({ teams, targets, headingRef }: {
  teams: Team[]
  targets: Targets
  headingRef: Ref<HTMLHeadingElement>
}) {
  const [showDetails, setShowDetails] = useState(false)
  const [hideSkill, setHideSkill] = useState(true)
  const [hidePositions, setHidePositions] = useState(true)
  const [copyStatus, setCopyStatus] = useState<{ text: string; copied: boolean }>()
  const hasCoverage = teams.some(team => team.positionCoverage)
  const text = teams.map(team => {
    const lines = [`${team.name} (${team.size} ${team.size === 1 ? 'player' : 'players'})`]
    if (!hideSkill) {
      lines.push(`Average skill: ${team.averageSkill.toFixed(2)} · Total skill: ${team.totalSkill}`)
    }
    for (const player of team.players) {
      lines.push(hideSkill ? player.name : `${player.name} (${player.skillRating}/5)`)
    }
    const coverage = team.positionCoverage
    if (!hidePositions && coverage) {
      const summary = positions.filter(position => targets[position] > 0)
        .map(position => `${position} ${coverage[position]}/${targets[position]}`)
      lines.push(`Position coverage: ${summary.join(' · ')}`)
    }
    return lines.join('\n')
  }).join('\n\n')

  async function copyDraw() {
    setCopyStatus(undefined)
    try {
      await navigator.clipboard.writeText(text)
      setCopyStatus({ text, copied: true })
    } catch {
      setCopyStatus({ text, copied: false })
    }
  }

  return (
    <section className="results" aria-labelledby="results-heading">
      <div className="section-title results-heading">
        <h2 id="results-heading" tabIndex={-1} ref={headingRef}>Generated teams</h2>
        <button type="button" className="primary copy-draw" onClick={copyDraw}>Copy draw</button>
      </div>
      <p className="hint" role="status">
        {copyStatus?.text === text && copyStatus.copied ? 'Draw copied.' :
          hideSkill && hidePositions ? 'Shares team names and player names.' : 'Shares the details selected below.'}
      </p>
      {copyStatus?.text === text && !copyStatus.copied && (
        <label className="add-form">
          Clipboard unavailable. Copy the draw from this field.
          <textarea readOnly rows={5} value={text} onFocus={event => event.currentTarget.select()} />
        </label>
      )}
      <div className="results-controls">
        <label className="organiser-details">
          <input type="checkbox" checked={showDetails} onChange={event => setShowDetails(event.target.checked)} />
          Show organiser details
        </label>
        <details className="share-draw">
          <summary>Sharing options</summary>
          <div className="share-controls">
            <label>
              <input type="checkbox" checked={!hideSkill} onChange={event => {
                setHideSkill(!event.target.checked)
                setCopyStatus(undefined)
              }} />
              Include skill ratings
            </label>
            {hasCoverage && <label>
              <input type="checkbox" checked={!hidePositions} onChange={event => {
                setHidePositions(!event.target.checked)
                setCopyStatus(undefined)
              }} />
              Include position coverage
            </label>}
          </div>
        </details>
      </div>
      <TeamResults teams={teams} targets={targets} hideSkill={!showDetails && hideSkill} hidePositions={!showDetails && hidePositions} />
    </section>
  )
}
