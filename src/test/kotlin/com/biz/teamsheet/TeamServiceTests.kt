package com.biz.teamsheet

import com.biz.teamsheet.model.BalancingMode
import com.biz.teamsheet.model.GameFormat
import com.biz.teamsheet.model.Player
import com.biz.teamsheet.model.Position.ATT
import com.biz.teamsheet.model.Position.DEF
import com.biz.teamsheet.model.Position.GK
import com.biz.teamsheet.model.Position.MID
import com.biz.teamsheet.model.TeamRequest
import com.biz.teamsheet.service.TeamService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TeamServiceTests {
    private val service = TeamService()
    private val sixAside = GameFormat("6-a-side", 6, mapOf(GK to 1, DEF to 2, MID to 1, ATT to 2))

    @Test
    fun `every mode keeps all players and balances an uneven roster`() {
        val players = List(13) { index -> Player("$index", "Player $index", index % 5 + 1) }
        for (mode in BalancingMode.entries) {
            val request = TeamRequest(players, 2, mode, sixAside, seed = 4)
            val result = service.generate(request)
            assertEquals(listOf(6, 7), result.teams.map { it.size }.sorted())
            assertEquals(players.toSet(), result.teams.flatMap { it.players }.toSet())
            assertEquals(result, service.generate(request))
            result.teams.forEach { team ->
                assertEquals(team.players.sumOf { it.skillRating }, team.totalSkill)
                assertEquals(team.totalSkill.toDouble() / team.size, team.averageSkill)
            }
        }
    }

    @Test
    fun `skill balancing uses averages for teams of different sizes`() {
        val players = listOf(1, 3, 3, 3, 5).mapIndexed { index, skill -> Player("$index", "Player $index", skill) }
        val teams = service.generate(TeamRequest(players, 2, BalancingMode.SKILL, sixAside, seed = 4)).teams
        assertTrue(teams.all { it.averageSkill == 3.0 })
        assertEquals(listOf(6, 9), teams.map { it.totalSkill }.sorted())
    }

    @Test
    fun `position coverage can use a secondary preference to fill a gap`() {
        val players = listOf(
            Player("one", "Flexible", 3, positions = listOf(GK, DEF)),
            Player("two", "Keeper", 3, positions = listOf(GK)),
            Player("three", "New player", 3),
        )
        val format = GameFormat("Custom", 2, mapOf(GK to 1, DEF to 1))
        val team = service.generate(TeamRequest(players, 1, BalancingMode.SKILL_AND_POSITION, format)).teams.single()
        assertEquals(3, team.size)
        assertEquals(1, team.positionCoverage?.get(GK))
        assertEquals(1, team.positionCoverage?.get(DEF))
    }

    @Test
    fun `position balancing spreads keepers across equal skill rosters`() {
        val players = List(6) { index ->
            Player("$index", "Player $index", 3, positions = if (index < 2) listOf(GK) else listOf(DEF))
        }
        val format = GameFormat("Custom", 3, mapOf(GK to 1, DEF to 2))
        for (seed in 1L..10L) {
            val teams = service.generate(TeamRequest(players, 2, BalancingMode.SKILL_AND_POSITION, format, seed)).teams
            assertTrue(teams.all { it.positionCoverage?.get(GK) == 1 && it.positionCoverage[DEF] == 2 })
        }
    }

    @Test
    fun `missing positions are allowed and a flexible player counts only once`() {
        val players = listOf(Player("one", "Flexible", 5, positions = listOf(DEF, ATT)), Player("two", "New", 1))
        val team = service.generate(TeamRequest(players, 1, BalancingMode.SKILL_AND_POSITION, sixAside, seed = 4)).teams.single()
        assertEquals(1, team.positionCoverage?.values?.sum())
        assertEquals(1, team.positionCoverage?.get(DEF))
        assertEquals(0, team.positionCoverage?.get(GK))
        assertEquals(3.0, team.averageSkill)
    }

    @Test
    fun `unknown positions produce the same rosters as skill balancing`() {
        val players = List(9) { index -> Player("$index", "Player $index", index % 5 + 1) }
        val request = TeamRequest(players, 2, BalancingMode.SKILL, sixAside, seed = 10)
        val skill = service.generate(request)
        val combined = service.generate(request.copy(balancingMode = BalancingMode.SKILL_AND_POSITION))
        assertEquals(skill.teams.map { it.players }, combined.teams.map { it.players })
    }

    @Test
    fun `five six-a-side teams spread the lowest ratings across redraws`() {
        val ratings = List(2) { 1 } + List(7) { 2 } + List(18) { 3 } + List(2) { 4 } + listOf(5)
        val players = ratings.mapIndexed { index, rating -> Player("$index", "Player $index", rating) }
        for (mode in listOf(BalancingMode.SKILL, BalancingMode.SKILL_AND_POSITION)) {
            val highestRatedTeamNames = mutableSetOf<String>()
            for (seed in 1L..20L) {
                val teams = service.generate(TeamRequest(players, 5, mode, sixAside, seed)).teams
                highestRatedTeamNames += teams.single { team -> team.players.any { it.skillRating == 5 } }.name
                assertTrue(teams.all { team -> team.players.count { it.skillRating == 1 } <= 1 }, "$mode seed $seed")
                assertTrue(teams.maxOf { it.totalSkill } - teams.minOf { it.totalSkill } <= 1, "$mode seed $seed")
            }
            assertEquals(5, highestRatedTeamNames.size, "$mode should use every team name for the highest-rated player")
        }
    }

    @Test
    fun `invalid rosters and formations are rejected`() {
        val players = listOf(Player("one", "Player one", 4), Player("two", "Player two", 2))
        val request = TeamRequest(players, 2, BalancingMode.RANDOM, sixAside)
        assertFailsWith<IllegalArgumentException> { service.generate(request.copy(numberOfTeams = 3)) }
        assertFailsWith<IllegalArgumentException> { service.generate(request.copy(players = List(2) { players.first() })) }
        assertFailsWith<IllegalArgumentException> { service.generate(request.copy(gameFormat = sixAside.copy(playersPerTeam = 5))) }
    }
}
