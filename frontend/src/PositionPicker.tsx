import { positions, positionNames, type Position } from './types'

export default function PositionPicker({ playerName, value, onChange }: {
  playerName: string
  value: Position[]
  onChange: (value: Position[]) => void
}) {
  return (
    <div className="position-buttons" role="group" aria-label={'Position preferences for ' + playerName}>
      {positions.map(position => {
        const rank = value.indexOf(position) + 1
        return <button type="button" className="position-button" key={position} aria-pressed={rank > 0}
          aria-label={positionNames[position] + (rank ? ', preference ' + rank : '')} onClick={() => onChange(
            rank > 0 ? value.filter(item => item !== position) : [...value, position],
          )}>
          {rank > 0 && <span>{rank}</span>}{position}
        </button>
      })}
    </div>
  )
}
