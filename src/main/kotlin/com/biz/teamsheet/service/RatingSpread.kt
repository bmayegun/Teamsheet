package com.biz.teamsheet.service

import com.biz.teamsheet.model.Player
import com.biz.teamsheet.model.Position

internal fun spreadRatingExtremes(rosters: List<MutableList<Player>>, targets: Map<Position, Int>?) {
    repeat(10) {
        var changed = false
        for (a in rosters.indices) for (b in a + 1 until rosters.size) {
            if (spreadBetween(rosters[a], rosters[b], targets)) changed = true
        }
        if (!changed) return
    }
}

private data class RatingMix(val low: Int, val high: Int) {
    val lowPairs: Int get() = low * (low - 1) / 2
    val highPairs: Int get() = high * (high - 1) / 2

    fun afterSwap(outgoing: RatingMix, incoming: RatingMix) =
        RatingMix(low - outgoing.low + incoming.low, high - outgoing.high + incoming.high)
}

private fun ratingMix(players: List<Player>) =
    RatingMix(players.count { it.skillRating == 1 }, players.count { it.skillRating >= 4 })

private data class PlayerPair(val first: Int, val second: Int, val total: Int, val mix: RatingMix)

private fun playerPairs(roster: List<Player>, balancePositions: Boolean): List<PlayerPair> {
    // Players with the same rating and preferences are interchangeable for this pass.
    val profiles = roster.map { it.skillRating to if (balancePositions) it.positions else emptyList() }
    return buildList {
        val seen = mutableSetOf<Set<Pair<Int, List<Position>>>>()
        for (a in roster.indices) for (b in a + 1 until roster.size) {
            if (seen.add(setOf(profiles[a], profiles[b]))) {
                add(PlayerPair(a, b, roster[a].skillRating + roster[b].skillRating, ratingMix(listOf(roster[a], roster[b]))))
            }
        }
    }
}

private fun spreadBetween(left: MutableList<Player>, right: MutableList<Player>, targets: Map<Position, Int>?): Boolean {
    val leftMix = ratingMix(left)
    val rightMix = ratingMix(right)
    val lowPairs = leftMix.lowPairs + rightMix.lowPairs
    val highPairs = leftMix.highPairs + rightMix.highPairs
    if (lowPairs + highPairs == 0 || left.size < 2 || right.size < 2) return false

    val rightPairs = playerPairs(right, targets != null).groupBy { it.total }
    val previousLeft by lazy { targets?.let { positionCoverage(left, it) } }
    val previousRight by lazy { targets?.let { positionCoverage(right, it) } }

    for (outgoing in playerPairs(left, targets != null)) {
        // Equal-total pairs leave both team size and total rating unchanged.
        for (incoming in rightPairs[outgoing.total].orEmpty()) {
            val nextLeft = leftMix.afterSwap(outgoing.mix, incoming.mix)
            val nextRight = rightMix.afterSwap(incoming.mix, outgoing.mix)
            val nextLowPairs = nextLeft.lowPairs + nextRight.lowPairs
            val nextHighPairs = nextLeft.highPairs + nextRight.highPairs
            if (nextLowPairs > lowPairs || nextHighPairs > highPairs || nextLowPairs + nextHighPairs == lowPairs + highPairs) continue

            val leftFirst = left[outgoing.first]
            val leftSecond = left[outgoing.second]
            val reversed = leftFirst.positions == right[incoming.second].positions && leftSecond.positions == right[incoming.first].positions
            val rightFirstIndex = if (reversed) incoming.second else incoming.first
            val rightSecondIndex = if (reversed) incoming.first else incoming.second
            val rightFirst = right[rightFirstIndex]
            val rightSecond = right[rightSecondIndex]
            val samePreferences = leftFirst.positions == rightFirst.positions && leftSecond.positions == rightSecond.positions

            // Read the original coverage before either roster is modified.
            val leftCoverage = if (samePreferences) null else previousLeft
            val rightCoverage = if (samePreferences) null else previousRight
            left[outgoing.first] = rightFirst
            left[outgoing.second] = rightSecond
            right[rightFirstIndex] = leftFirst
            right[rightSecondIndex] = leftSecond
            if (targets == null || leftCoverage == null || rightCoverage == null ||
                (positionCoverage(left, targets).preserves(leftCoverage) &&
                    positionCoverage(right, targets).preserves(rightCoverage))
            ) return true

            left[outgoing.first] = leftFirst
            left[outgoing.second] = leftSecond
            right[rightFirstIndex] = rightFirst
            right[rightSecondIndex] = rightSecond
        }
    }
    return false
}

private fun PositionCoverage.preserves(previous: PositionCoverage): Boolean =
    previous.counts.all { (position, count) -> counts.getValue(position) >= count }
