import { useId, type ReactNode } from 'react'
import SkillRating from './SkillRating'
import PositionPicker from './PositionPicker'
import type { Player } from './types'

interface Props {
  player: Player
  onChange: (player: Player) => void
  onRemove: () => void
  matchNumber?: number
  collapsed?: boolean
  removeOnRow?: boolean
  children?: ReactNode
}

export default function PlayerEditor({ player, onChange, onRemove, collapsed, removeOnRow, matchNumber, children }: Props) {
  const nameId = useId()

  const removeButton = <button className="remove" type="button" onClick={onRemove}
    aria-label={'Remove ' + (player.name || 'unnamed player')}>Remove</button>

  const editor = <>
    <div className="player-details">
      <div className="flex min-w-0 items-center gap-2">
        <label className="sr-only" htmlFor={nameId}>Player name</label>
        <input required id={nameId} value={player.name} onChange={event => onChange({ ...player, name: event.target.value })} />
      </div>
      <SkillRating playerName={player.name} value={player.skillRating}
        onChange={skillRating => onChange({ ...player, skillRating })} />
      {(!collapsed || !removeOnRow) && removeButton}
    </div>
    <PositionPicker playerName={player.name} value={player.positions}
      onChange={positions => onChange({ ...player, positions })} />
    {children}
  </>

  return (
    <li className={collapsed && removeOnRow ? 'player guest-with-remove' : 'player'}>
      {collapsed ? <details className="player-disclosure">
        <summary>
          {matchNumber !== undefined && <span className="match-number" aria-label={`Match participant ${matchNumber}`}>{matchNumber}</span>}
          <span>{player.name || 'Unnamed player'}</span>
        </summary>
        {editor}
      </details> : editor}
      {collapsed && removeOnRow && removeButton}
    </li>
  )
}
