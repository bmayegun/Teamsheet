import { useId } from 'react'
import { positions, positionNames, type Player, type Position } from './types'

interface Props {
  player: Player
  onChange: (player: Player) => void
  onRemove: () => void
}

const ratings = ['Beginner', 'Below average', 'Average', 'Good', 'Excellent']

export default function PlayerEditor({ player, onChange, onRemove }: Props) {
  const nameId = useId()
  const ratingId = useId()

  function changePosition(position: Position) {
    const selected = player.positions.includes(position)
    onChange({
      ...player,
      positions: selected ? player.positions.filter(item => item !== position) : [...player.positions, position],
    })
  }

  return (
    <li className="player">
      <div className="player-details">
        <div>
          <label className="sr-only" htmlFor={nameId}>Player name</label>
          <input required id={nameId} value={player.name} onChange={event => onChange({ ...player, name: event.target.value })} />
        </div>
        <fieldset className="skill-rating">
          <legend className="sr-only">Skill for {player.name}</legend>
          {ratings.map((description, index) => {
            const score = index + 1
            const label = `${score} · ${description}`
            return (
              <label className="skill-segment" data-filled={score <= player.skillRating} key={score} title={label}>
                <input className="sr-only" type="radio" name={ratingId} value={score}
                  aria-label={label} checked={player.skillRating === score}
                  onChange={() => onChange({ ...player, skillRating: score })} />
                <span aria-hidden="true">{score}</span>
              </label>
            )
          })}
        </fieldset>
        <button className="remove" type="button" onClick={onRemove} aria-label={'Remove ' + player.name}>×</button>
      </div>
      <div className="position-buttons" role="group" aria-label={'Position preferences for ' + player.name}>
        {positions.map(position => {
          const rank = player.positions.indexOf(position) + 1
          return <button type="button" className="position-button" key={position} aria-pressed={rank > 0}
            aria-label={positionNames[position] + (rank ? ', preference ' + rank : '')} onClick={() => changePosition(position)}>
            {rank > 0 && <span>{rank}</span>}{position}
          </button>
        })}
      </div>
    </li>
  )
}
