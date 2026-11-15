import { positions, positionNames, type Targets, type Team } from './types'

export default function TeamResults({
  teams, targets, hideSkill = false, hidePositions = false,
}: { teams: Team[]; targets: Targets; hideSkill?: boolean; hidePositions?: boolean }) {
  return <div className="results-grid">
    {teams.map(team => <article className="card team-card" key={team.name}>
      <div className="section-title"><h3>{team.name}</h3><span className="count">{team.size} players</span></div>
      {!hideSkill && <dl className="team-stats">
        <div><dt>Average skill</dt><dd>{team.averageSkill.toFixed(2)}</dd></div>
        <div><dt>Total skill</dt><dd>{team.totalSkill}</dd></div>
      </dl>}
      <ul className="team-players">
        {team.players.map(player => <li key={player.id}>
          <span>{player.name}</span>
          {!hideSkill && <span className="player-score" aria-label={'Skill ' + player.skillRating}>{player.skillRating}</span>}
        </li>)}
      </ul>
      {!hidePositions && team.positionCoverage && <div className="team-coverage">
        <h4>Position coverage</h4>
        <div className="position-buttons">
          {positions.filter(position => targets[position] > 0).map(position =>
            <span key={position} title={positionNames[position]}>{position} {team.positionCoverage![position]}/{targets[position]}</span>)}
        </div>
      </div>}
    </article>)}
  </div>
}
