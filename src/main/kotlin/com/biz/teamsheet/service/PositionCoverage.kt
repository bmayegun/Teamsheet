package com.biz.teamsheet.service

import com.biz.teamsheet.model.Player
import com.biz.teamsheet.model.Position

internal data class PositionCoverage(val counts: Map<Position, Int>, val preferenceCost: Int)

// Minimum-cost maximum matching. Residual edges allow a flexible player to move
// to another preference when that opens a slot for a less flexible player.
internal fun positionCoverage(players: List<Player>, targets: Map<Position, Int>): PositionCoverage {
    data class Edge(val to: Int, val reverse: Int, var capacity: Int, val cost: Int)

    val known = players.filter { it.positions.isNotEmpty() }
    val positionStart = known.size + 1
    val sink = positionStart + Position.entries.size
    val graph = List(sink + 1) { mutableListOf<Edge>() }

    fun connect(from: Int, to: Int, capacity: Int, cost: Int): Edge {
        val edge = Edge(to, graph[to].size, capacity, cost)
        graph[to].add(Edge(from, graph[from].size, 0, -cost))
        graph[from].add(edge)
        return edge
    }

    known.forEachIndexed { index, player ->
        connect(0, index + 1, 1, 0)
        player.positions.forEachIndexed { rank, position ->
            connect(index + 1, positionStart + position.ordinal, 1, rank)
        }
    }
    val slots = Position.entries.associateWith { position ->
        connect(positionStart + position.ordinal, sink, targets.getValue(position), 0)
    }

    var cost = 0
    while (true) {
        val distance = IntArray(graph.size) { Int.MAX_VALUE }
        val previousNode = IntArray(graph.size)
        val previousEdge = IntArray(graph.size)
        val queued = BooleanArray(graph.size)
        val queue = ArrayDeque<Int>()
        distance[0] = 0
        queue.addLast(0)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            queued[node] = false
            graph[node].forEachIndexed { index, edge ->
                val nextDistance = distance[node] + edge.cost
                if (edge.capacity > 0 && nextDistance < distance[edge.to]) {
                    distance[edge.to] = nextDistance
                    previousNode[edge.to] = node
                    previousEdge[edge.to] = index
                    if (!queued[edge.to]) {
                        queue.addLast(edge.to)
                        queued[edge.to] = true
                    }
                }
            }
        }
        if (distance[sink] == Int.MAX_VALUE) break

        cost += distance[sink]
        var node = sink
        while (node != 0) {
            val from = previousNode[node]
            val edge = graph[from][previousEdge[node]]
            edge.capacity--
            graph[node][edge.reverse].capacity++
            node = from
        }
    }
    return PositionCoverage(slots.mapValues { (position, edge) -> targets.getValue(position) - edge.capacity }, cost)
}
