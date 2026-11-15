package com.biz.teamsheet

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import tools.jackson.databind.json.JsonMapper

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TeamApiTests {
    @LocalServerPort
    private var port: Int = 0

    private val client = HttpClient.newHttpClient()
    private val json = JsonMapper.builder().build()
    private val request = """
        {
          "players": [
            {"id": "1", "name": "New player", "skillRating": 3},
            {"id": "2", "name": "Keeper", "skillRating": 5, "positions": ["GK"]},
            {"id": "3", "name": "Defender", "skillRating": 1, "positions": ["DEF"]}
          ],
          "numberOfTeams": 2,
          "balancingMode": "SKILL_AND_POSITION",
          "gameFormat": {"name": "Custom", "playersPerTeam": 2, "positionTargets": {"GK": 1, "DEF": 1}},
          "seed": 1
        }
    """.trimIndent()

    @Test
    fun `generation returns roster stats and coverage without a lineup`() {
        val response = post(request)
        assertEquals(200, response.statusCode(), response.body())
        val teams = json.readTree(response.body()).path("teams").toList()
        assertEquals(listOf(1, 2), teams.map { it.path("size").asInt() }.sorted())
        assertEquals(setOf("1", "2", "3"), teams.flatMap { it.path("players").toList().map { player -> player.path("id").asString() } }.toSet())
        assertTrue(teams.all { it.has("averageSkill") && it.has("totalSkill") && it.has("positionCoverage") })
        assertTrue(teams.flatMap { it.path("players").toList() }.none { it.has("assignedPosition") })
    }

    @Test
    fun `invalid team counts return a readable error`() {
        val response = post(request.replace("\"numberOfTeams\": 2", "\"numberOfTeams\": 4"))
        assertEquals(400, response.statusCode())
        assertEquals("Each team needs at least one player.", json.readTree(response.body()).path("message").asString())
    }

    private fun post(body: String): HttpResponse<String> = client.send(
        HttpRequest.newBuilder(URI.create("http://localhost:$port/api/teams/generate"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString(),
    )
}
