import { useId } from 'react'

const ratings = ['Beginner', 'Below average', 'Average', 'Good', 'Excellent']

export default function SkillRating({ playerName, value, onChange }: {
  playerName: string
  value: number
  onChange: (value: number) => void
}) {
  const ratingId = useId()

  return (
    <div className="skill-rating-control">
      <fieldset className="skill-rating">
        <legend className="sr-only">Skill for {playerName}</legend>
        {ratings.map((description, index) => {
          const score = index + 1
          const label = `${score} · ${description}`
          return (
            <label className="skill-segment" data-filled={score <= value} key={score}>
              <input className="sr-only" type="radio" name={ratingId} value={score}
                aria-label={label} checked={value === score}
                onChange={() => onChange(score)} />
              <span aria-hidden="true">{score}</span>
            </label>
          )
        })}
      </fieldset>
    </div>
  )
}
