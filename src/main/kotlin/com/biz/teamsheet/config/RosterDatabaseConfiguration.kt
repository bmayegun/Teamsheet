package com.biz.teamsheet.config

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.context.annotation.Primary
import org.springframework.core.env.Environment
import java.net.URI

@Configuration(proxyBeanMethods = false)
@Profile("roster")
class RosterDatabaseConfiguration {
    @Bean
    @Primary
    fun rosterDataSource(environment: Environment): HikariDataSource {
        val database = connection(environment, environment.getProperty("DATABASE_URL"))
        val poolSize = environment.getProperty("ROSTER_DB_POOL_SIZE", Int::class.java) ?: 5

        return HikariDataSource(HikariConfig().apply {
            jdbcUrl = database.jdbcUrl
            username = database.username
            password = database.password
            maximumPoolSize = poolSize
            minimumIdle = 1
            connectionTimeout = 5_000
            poolName = "teamsheet-postgres"
        })
    }

    @Bean
    fun neonFlywayConfigurationCustomizer(environment: Environment): FlywayConfigurationCustomizer =
        FlywayConfigurationCustomizer { configuration ->
            val migrationUrl = environment.getProperty("DATABASE_URL_UNPOOLED")
                ?: environment.getProperty("DATABASE_URL")
                ?: return@FlywayConfigurationCustomizer
            val database = connection(environment, migrationUrl)
            configuration.dataSource(database.jdbcUrl, database.username, database.password)
        }

    private fun connection(environment: Environment, neonUrl: String?): DatabaseConnection {
        if (!neonUrl.isNullOrBlank()) return parsePostgresUrl(neonUrl)

        val legacyUrl = environment.getProperty("ROSTER_JDBC_URL")
            ?: environment.getProperty("teamsheet.roster.jdbc-url")
            ?: error("Set DATABASE_URL or ROSTER_JDBC_URL to connect to PostgreSQL.")
        val jdbcUrl = if (legacyUrl.startsWith("jdbc:postgresql:")) legacyUrl else parsePostgresUrl(legacyUrl).jdbcUrl
        return DatabaseConnection(
            jdbcUrl = jdbcUrl,
            username = environment.getProperty("ROSTER_DB_USERNAME"),
            password = environment.getProperty("ROSTER_DB_PASSWORD"),
        )
    }

    private fun parsePostgresUrl(value: String): DatabaseConnection {
        if (value.startsWith("jdbc:postgresql:")) {
            return DatabaseConnection(value, null, null)
        }

        val uri = URI(value)
        require(uri.scheme == "postgresql" || uri.scheme == "postgres") {
            "PostgreSQL connection URL must use the postgresql:// scheme."
        }
        val authority = uri.rawAuthority.substringAfterLast('@')
        val path = uri.rawPath.orEmpty()
        val query = uri.rawQuery?.let { "?$it" }.orEmpty()
        val userInfo = uri.userInfo
        val username = userInfo?.substringBefore(':')
        val password = userInfo?.substringAfter(':', "")?.takeIf { userInfo.contains(':') }

        return DatabaseConnection(
            jdbcUrl = "jdbc:postgresql://$authority$path$query",
            username = username,
            password = password,
        )
    }

    private data class DatabaseConnection(
        val jdbcUrl: String,
        val username: String?,
        val password: String?,
    )
}
