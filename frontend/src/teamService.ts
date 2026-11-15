import type { Team, TeamRequest } from './types'

export async function generateTeams(request: TeamRequest, signal: AbortSignal): Promise<Team[]> {
  const response = await fetch('/api/teams/generate', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(request),
    signal,
  })
  if (!response.ok) {
    const body = await response.json().catch(() => null)
    throw new Error(body?.message ?? 'Team generation is unavailable. Try again.')
  }
  const body: { teams: Team[] } = await response.json()
  return body.teams
}
