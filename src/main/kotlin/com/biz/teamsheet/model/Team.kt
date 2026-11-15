package com.biz.teamsheet.model

data class Team(
    val name: String,
    val players: List<Player>,
    val positionCoverage: Map<Position, Int>? = null,
) {
    val size: Int get() = players.size
    val totalSkill: Int get() = players.sumOf { it.skillRating }
    val averageSkill: Double get() = totalSkill.toDouble() / size
}
