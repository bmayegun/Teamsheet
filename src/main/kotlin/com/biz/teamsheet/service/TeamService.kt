package com.biz.teamsheet.service

import com.biz.teamsheet.model.BalancingMode
import com.biz.teamsheet.model.Player
import com.biz.teamsheet.model.Position
import com.biz.teamsheet.model.Team
import com.biz.teamsheet.model.TeamRequest
import com.biz.teamsheet.model.TeamResponse
import kotlin.random.Random
import org.springframework.stereotype.Service

@Service
class TeamService {
    fun generate(request: TeamRequest): TeamResponse {
        val players = request.players
        val teamCount = request.numberOfTeams
        val format = request.gameFormat
        require(players.size <= MAX_PLAYERS) { "Enter at most $MAX_PLAYERS players." }
        require(teamCount in 1..players.size) { "Each team needs at least one player." }
        require(players.all { it.id.isNotBlank() && it.name.isNotBlank() }) { "Every player needs an ID and name." }
        require(players.map { it.id }.toSet().size == players.size) { "Player IDs must be unique." }
        require(players.all { it.skillRating in 1..5 }) { "Skill ratings must be between 1 and 5." }
        require(players.all { it.positions.distinct().size == it.positions.size }) { "List each position only once per player." }
        require(format.playersPerTeam in 1..MAX_PLAYERS) { "Team size must be between 1 and $MAX_PLAYERS." }
        val targets = Position.entries.associateWith { format.positionTargets[it] ?: 0 }
        require(targets.values.all { it in 0..format.playersPerTeam } && targets.values.sum() == format.playersPerTeam) {
            "Position targets must be nonnegative and add up to the selected team size."
        }

        val random = request.seed?.let { Random(it) } ?: Random.Default
        val shuffled = players.shuffled(random)
        val capacities = List(teamCount) { index ->
            players.size / teamCount + if (index < players.size % teamCount) 1 else 0
        }.shuffled(random)
        val rosters = capacities.map { mutableListOf<Player>() }
        val showCoverage = request.balancingMode == BalancingMode.SKILL_AND_POSITION
        val balancePositions = showCoverage && players.any { it.positions.isNotEmpty() }

        if (request.balancingMode == BalancingMode.RANDOM) {
            var offset = 0
            rosters.forEachIndexed { index, roster ->
                val end = offset + capacities[index]
                roster.addAll(shuffled.subList(offset, end))
                offset = end
            }
        } else {
            val totals = IntArray(teamCount)
            val average = players.map { it.skillRating }.average()
            for (player in shuffled.sortedByDescending { it.skillRating }) {
                val team = rosters.indices.filter { rosters[it].size < capacities[it] }
                    .minBy { (totals[it] - average * rosters[it].size) / capacities[it] }
                rosters[team].add(player)
                totals[team] += player.skillRating
            }
            improve(rosters, totals, targets, balancePositions, format.playersPerTeam, average)
        }

        // Assign team names after balancing so placement order cannot favour a team number.
        val orderedRosters = if (request.balancingMode == BalancingMode.RANDOM) rosters else rosters.shuffled(random)
        return TeamResponse(orderedRosters.mapIndexed { index, roster ->
            Team("Team ${index + 1}", roster, if (showCoverage) positionCoverage(roster, targets).counts else null)
        })
    }

    private fun improve(
        rosters: List<MutableList<Player>>,
        totals: IntArray,
        targets: Map<Position, Int>,
        balancePositions: Boolean,
        targetSize: Int,
        average: Double,
    ) {
        fun penalty(roster: List<Player>): Double {
            if (!balancePositions) return 0.0
            val coverage = positionCoverage(roster, targets)
            val missing = targetSize - coverage.counts.values.sum()
            return (missing.toDouble() / targetSize) * 0.5 +
                (coverage.preferenceCost.toDouble() / targetSize) * 0.01
        }

        val penalties = rosters.map(::penalty).toMutableList()
        val averages = totals.mapIndexed { index, total -> total.toDouble() / rosters[index].size }.toMutableList()
        val ratingCounts = rosters.map { roster ->
            IntArray(6).also { counts -> roster.forEach { counts[it.skillRating]++ } }
        }

        fun skillGap(): Double = (averages.max() - averages.min()) / 4
        fun positionGap(): Double = 0.75 * penalties.average() + 0.25 * penalties.max()
        fun score(): Double = skillGap() + positionGap()
        fun skillDeviation(): Double = averages.sumOf { (it - average) * (it - average) }

        var current = score()
        var currentDeviation = skillDeviation()
        repeat(10) {
            var changed = false
            for (a in rosters.indices) for (b in a + 1 until rosters.size) {
                val left = rosters[a]
                val right = rosters[b]
                if (left.size == 1 && right.size == 1) continue
                for (i in left.indices) for (j in right.indices) {
                    val first = left[i]
                    val second = right[j]
                    if (first.skillRating == second.skillRating &&
                        (!balancePositions || first.positions == second.positions)) continue

                    val oldLeftPenalty = penalties[a]
                    val oldRightPenalty = penalties[b]
                    val oldLeftAverage = averages[a]
                    val oldRightAverage = averages[b]
                    val delta = second.skillRating - first.skillRating
                    left[i] = second
                    right[j] = first
                    if (first.positions != second.positions) {
                        penalties[a] = penalty(left)
                        penalties[b] = penalty(right)
                    }
                    averages[a] = (totals[a] + delta).toDouble() / left.size
                    averages[b] = (totals[b] - delta).toDouble() / right.size
                    val candidate = score()
                    val deviation = skillDeviation()
                    val firstRating = first.skillRating
                    val secondRating = second.skillRating
                    // Change in same-rating pairs across the two teams.
                    val pairDelta = if (firstRating == secondRating) 0 else
                        ratingCounts[a][secondRating] - ratingCounts[a][firstRating] +
                            ratingCounts[b][firstRating] - ratingCounts[b][secondRating] + 2
                    val improvesTie = kotlin.math.abs(candidate - current) <= 1e-9 &&
                        (deviation < currentDeviation - 1e-9 ||
                            (kotlin.math.abs(deviation - currentDeviation) <= 1e-9 && pairDelta < 0))
                    if (candidate < current - 1e-9 || improvesTie) {
                        totals[a] += delta
                        totals[b] -= delta
                        ratingCounts[a][firstRating]--
                        ratingCounts[a][secondRating]++
                        ratingCounts[b][secondRating]--
                        ratingCounts[b][firstRating]++
                        current = candidate
                        currentDeviation = deviation
                        changed = true
                    } else {
                        left[i] = first
                        right[j] = second
                        penalties[a] = oldLeftPenalty
                        penalties[b] = oldRightPenalty
                        averages[a] = oldLeftAverage
                        averages[b] = oldRightAverage
                    }
                }
            }
            if (!changed) return
        }
    }

    companion object {
        const val MAX_PLAYERS = 100
    }
}
