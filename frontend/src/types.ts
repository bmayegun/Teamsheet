export const positions = ['GK', 'DEF', 'MID', 'ATT'] as const
export type Position = typeof positions[number]
export type Targets = Record<Position, number>
export type Mode = 'RANDOM' | 'SKILL' | 'SKILL_AND_POSITION'

export const positionNames: Record<Position, string> = {
  GK: 'Goalkeeper', DEF: 'Defender', MID: 'Midfielder', ATT: 'Attacker',
}

export const presets: Record<string, Targets> = {
  '5-a-side': { GK: 1, DEF: 1, MID: 2, ATT: 1 },
  '6-a-side': { GK: 1, DEF: 2, MID: 1, ATT: 2 },
  '7-a-side': { GK: 1, DEF: 2, MID: 2, ATT: 2 },
  '11-a-side': { GK: 1, DEF: 4, MID: 3, ATT: 3 },
}

export interface Player {
  id: string
  name: string
  skillRating: number
  positions: Position[]
}

export interface TeamRequest {
  players: Player[]
  numberOfTeams: number
  balancingMode: Mode
  gameFormat: { name: string; playersPerTeam: number; positionTargets: Targets }
}

export interface Team {
  name: string
  players: Player[]
  positionCoverage: Targets | null
  size: number
  averageSkill: number
  totalSkill: number
}
