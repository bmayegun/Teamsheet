package com.biz.teamsheet.roster

import com.biz.teamsheet.auth.AuthenticationNetworkFilter
import com.biz.teamsheet.auth.AuthenticationNetworkLimits
import com.biz.teamsheet.auth.AccountAuthentication
import com.biz.teamsheet.auth.CredentialsVersionFilter
import com.biz.teamsheet.auth.EmailSignInStore
import org.springframework.security.web.access.intercept.AuthorizationFilter
import com.biz.teamsheet.auth.ClientAddress
import com.biz.teamsheet.auth.rosterAccount
import com.biz.teamsheet.club.ClubStore
import org.springframework.boot.web.servlet.ServletListenerRegistrationBean
import org.springframework.security.web.csrf.CsrfFilter
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler
import org.springframework.security.web.authentication.AuthenticationSuccessHandler
import org.springframework.web.util.HttpSessionMutexListener
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException
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
    private val logger = LoggerFactory.getLogger(javaClass)
    @Bean
    @Profile("roster")
    fun privateRosterFilterChain(http: HttpSecurity, clubs: ClubStore, addresses: ClientAddress, limits: AuthenticationNetworkLimits, identities: EmailSignInStore): SecurityFilterChain = http
        .authorizeHttpRequests {
            it.requestMatchers("/api/account/**", "/api/roster", "/api/roster/**", "/api/clubs", "/api/clubs/**").authenticated()
                .anyRequest().permitAll()
        }
        .csrf { it.ignoringRequestMatchers("/api/teams/generate") }
        .addFilterBefore(CredentialsVersionFilter(identities), AuthorizationFilter::class.java)
        .addFilterAfter(AuthenticationNetworkFilter(addresses, limits), CsrfFilter::class.java)
        .requestCache { it.disable() }
        .exceptionHandling { it.authenticationEntryPoint(HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)) }
        .oauth2Login { it.successHandler(googleSuccessHandler(clubs, identities)).failureUrl("/?signInFailed=1") }
        .logout { it.logoutSuccessUrl("/") }
        .build()

    internal fun googleSuccessHandler(clubs: ClubStore, identities: EmailSignInStore): AuthenticationSuccessHandler {
        val redirect = SimpleUrlAuthenticationSuccessHandler("/")
        redirect.setAlwaysUseDefaultTargetUrl(true)
        return AuthenticationSuccessHandler { request, response, authentication ->
            rosterAccount(authentication.principal)?.let { account ->
                request.session.setAttribute(AccountAuthentication.CREDENTIALS_VERSION, identities.credentialsVersion(account.subject))
                try {
                    clubs.updateDisplayName(account.subject, account.displayName)
                } catch (error: DataAccessException) {
                    logger.warn("Could not refresh member name after Google sign-in ({})", error.javaClass.simpleName)
                }
            }
            redirect.onAuthenticationSuccess(request, response, authentication)
        }
    }

    @Bean
    @Profile("roster")
    fun sessionMutexListener() = ServletListenerRegistrationBean(HttpSessionMutexListener())

    @Bean
    @Profile("!roster")
    fun publicSecurity(http: HttpSecurity): SecurityFilterChain = http
        .authorizeHttpRequests { it.anyRequest().permitAll() }
        .csrf { it.ignoringRequestMatchers("/api/teams/generate") }
        .build()
}
