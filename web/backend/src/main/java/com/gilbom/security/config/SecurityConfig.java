package com.gilbom.security.config;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestHandler;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import com.gilbom.audit.SecurityAuditService;
import com.gilbom.auth.session.AuthenticationSessionEnforcementFilter;
import com.gilbom.auth.session.SessionSecurityStateRepository;
import com.gilbom.common.error.ErrorCode;
import com.gilbom.common.web.AccessDeniedErrorCodeResolver;
import com.gilbom.security.web.SecurityErrorResponseWriter;

@Configuration
@EnableMethodSecurity
@EnableConfigurationProperties(GilbomCorsProperties.class)
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            SecurityErrorResponseWriter errorWriter,
            CsrfTokenRepository csrfTokenRepository,
            CsrfTokenRequestHandler csrfTokenRequestHandler,
            SecurityContextRepository securityContextRepository,
            CorsConfigurationSource corsConfigurationSource,
            SessionSecurityStateRepository sessionStateRepository,
            ObjectProvider<SecurityAuditService> securityAuditProvider
    ) throws Exception {
        http
                // SPA가 JSON API를 사용하므로 Spring 기본 HTML login과 Basic 인증은 열지 않는다.
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                .requestCache(cache -> cache.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfTokenRepository)
                        .csrfTokenRequestHandler(csrfTokenRequestHandler))
                .securityContext(context -> context
                        .securityContextRepository(securityContextRepository)
                        .requireExplicitSave(true))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, exception) ->
                                errorWriter.write(
                                        request,
                                        response,
                                        ErrorCode.AUTH_UNAUTHENTICATED
                                ))
                        .accessDeniedHandler((request, response, exception) -> {
                            if (exception instanceof CsrfException) {
                                recordCsrfFailure(
                                        securityAuditProvider,
                                        request
                                );
                                errorWriter.write(
                                        request,
                                        response,
                                        ErrorCode.AUTH_FORBIDDEN,
                                        "요청 검증에 실패했습니다."
                                );
                                return;
                            }
                            recordAccessDenied(
                                    securityAuditProvider,
                                    request
                            );
                            errorWriter.write(
                                    request,
                                    response,
                                    AccessDeniedErrorCodeResolver.resolve(request)
                            );
                        }))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.GET,
                                "/api/health",
                                "/actuator/health",
                                "/actuator/health/**",
                                "/api/v1/system/health",
                                "/api/v1/system/info",
                                "/api/v1/auth/csrf-token",
                                "/api/v1/auth/session-state")
                        .permitAll()
                        // 공개 인증 API도 CSRF 검사를 통과해야 하며 인증만 요구하지 않는다.
                        .requestMatchers(HttpMethod.POST,
                                "/api/v1/auth/login",
                                "/api/v1/auth/password/initial-change",
                                "/api/v1/auth/mfa/totp/enroll",
                                "/api/v1/auth/mfa/totp/confirm",
                                "/api/v1/auth/mfa/totp/verify",
                                "/api/v1/auth/mfa/recovery",
                                "/api/v1/auth/logout",
                                "/api/v1/auth/email-availability",
                                "/api/v1/auth/registrations")
                        .permitAll()
                        .requestMatchers("/error")
                        .permitAll()
                        // 명시하지 않은 API는 새 기능이 추가돼도 자동 공개되지 않는다.
                        .anyRequest()
                        .authenticated())
                .addFilterAfter(
                        new AuthenticationSessionEnforcementFilter(
                                sessionStateRepository,
                                errorWriter,
                                Clock.systemUTC()
                        ),
                        SecurityContextHolderFilter.class
                );

        return http.build();
    }

    private static void recordCsrfFailure(
            ObjectProvider<SecurityAuditService> provider,
            jakarta.servlet.http.HttpServletRequest request
    ) {
        SecurityAuditService service = provider.getIfAvailable();
        if (service != null) {
            service.recordCsrfFailed(
                    request,
                    SecurityContextHolder.getContext().getAuthentication()
            );
        }
    }

    private static void recordAccessDenied(
            ObjectProvider<SecurityAuditService> provider,
            jakarta.servlet.http.HttpServletRequest request
    ) {
        SecurityAuditService service = provider.getIfAvailable();
        if (service != null) {
            service.recordAccessDenied(
                    request,
                    SecurityContextHolder.getContext().getAuthentication()
            );
        }
    }

    @Bean
    public CsrfTokenRepository csrfTokenRepository() {
        HttpSessionCsrfTokenRepository repository =
                new HttpSessionCsrfTokenRepository();
        repository.setHeaderName("X-CSRF-TOKEN");
        return repository;
    }

    @Bean
    public CsrfTokenRequestHandler csrfTokenRequestHandler() {
        // JSON으로 토큰을 전달해도 Spring Security 6의 BREACH 완화 마스킹은 유지한다.
        return new XorCsrfTokenRequestAttributeHandler();
    }

    @Bean
    public SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource(
            GilbomCorsProperties properties
    ) {
        UrlBasedCorsConfigurationSource source =
                new UrlBasedCorsConfigurationSource();
        if (!properties.isEnabled()) {
            return source;
        }

        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(properties.getAllowedOrigins());
        configuration.setAllowCredentials(properties.isAllowCredentials());
        configuration.setAllowedMethods(List.of(
                "GET",
                "HEAD",
                "POST",
                "PUT",
                "PATCH",
                "DELETE",
                "OPTIONS"
        ));
        configuration.setAllowedHeaders(List.of(
                "Accept",
                "Content-Type",
                "X-CSRF-TOKEN",
                "Idempotency-Key",
                "X-Request-Id"
        ));
        configuration.setExposedHeaders(List.of(
                "X-Request-Id",
                "Retry-After"
        ));
        configuration.setMaxAge(Duration.ofMinutes(10));
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }

    @Bean
    public UserDetailsService userDetailsService() {
        // DB 인증 구현 전 Spring Boot가 임의의 기본 사용자·비밀번호를 만들지 못하게 한다.
        return username -> {
            throw new UsernameNotFoundException("Authentication failed");
        };
    }
}
