package com.biz.teamsheet.model

enum class BalancingMode {
    RANDOM, SKILL, SKILL_AND_POSITION
}

data class GameFormat(
    val name: String,
    val playersPerTeam: Int,
    val positionTargets: Map<Position, Int>,
)

data class TeamRequest(
    val players: List<Player>,
    val numberOfTeams: Int,
    val balancingMode: BalancingMode,
    val gameFormat: GameFormat,
    val seed: Long? = null,
)

data class TeamResponse(val teams: List<Team>)
