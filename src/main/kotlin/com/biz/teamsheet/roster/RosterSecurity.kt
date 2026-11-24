package com.biz.teamsheet.roster

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpStatus
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.HttpStatusEntryPoint

@Configuration
@EnableWebSecurity
class RosterSecurity {
    @Bean
    @Profile("roster")
    fun privateRosterFilterChain(http: HttpSecurity): SecurityFilterChain = http
        .authorizeHttpRequests {
            it.requestMatchers("/api/roster", "/api/roster/**", "/api/clubs", "/api/clubs/**").authenticated()
                .anyRequest().permitAll()
        }
        .csrf { it.ignoringRequestMatchers("/api/teams/generate") }
        .requestCache { it.disable() }
        .exceptionHandling { it.authenticationEntryPoint(HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)) }
        .oauth2Login { it.defaultSuccessUrl("/", true).failureUrl("/?signInFailed=1") }
        .logout { it.logoutSuccessUrl("/") }
        .build()

    @Bean
    @Profile("!roster")
    fun publicSecurity(http: HttpSecurity): SecurityFilterChain = http
        .authorizeHttpRequests { it.anyRequest().permitAll() }
        .csrf { it.ignoringRequestMatchers("/api/teams/generate") }
        .build()
}
