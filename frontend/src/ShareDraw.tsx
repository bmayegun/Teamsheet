import { useState } from 'react'
import TeamResults from './TeamResults'
import { positions, type Targets, type Team } from './types'

export default function ShareDraw({ teams, targets }: { teams: Team[]; targets: Targets }) {
  const [hideSkill, setHideSkill] = useState(true)
  const [hidePositions, setHidePositions] = useState(true)
  const [copyStatus, setCopyStatus] = useState<{ text: string; copied: boolean }>()
  const hasCoverage = teams.some(team => team.positionCoverage)
  const text = teams.map(team => {
    const lines = [`${team.name} (${team.size} players)`]
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
    <details className="card share-draw">
      <summary>Share draw</summary>
      <div className="share-controls">
        <label>
          <input type="checkbox" role="switch" checked={hideSkill} onChange={event => {
            setHideSkill(event.target.checked)
            setCopyStatus(undefined)
          }} />
          Hide skill ratings
        </label>
        {hasCoverage && <label>
          <input type="checkbox" role="switch" checked={hidePositions} onChange={event => {
            setHidePositions(event.target.checked)
            setCopyStatus(undefined)
          }} />
          Hide position coverage
        </label>}
        <button type="button" className="secondary" onClick={copyDraw}>Copy draw</button>
        <span role="status">{copyStatus?.text === text && copyStatus.copied ? 'Draw copied' : ''}</span>
      </div>
      {copyStatus?.text === text && !copyStatus.copied && (
        <label className="mt-3 block">
          Clipboard unavailable. Copy the draw from this field.
          <textarea readOnly rows={5} value={text} onFocus={event => event.currentTarget.select()} />
        </label>
      )}
      <TeamResults teams={teams} targets={targets} hideSkill={hideSkill} hidePositions={hidePositions} />
    </details>
  )
}
