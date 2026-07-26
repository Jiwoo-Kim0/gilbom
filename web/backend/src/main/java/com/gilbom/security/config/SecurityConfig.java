package com.gilbom.security.config;

import java.util.HashMap;
import java.util.Map;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;

import com.gilbom.common.error.ErrorCode;
import com.gilbom.security.web.SecurityErrorResponseWriter;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            SecurityErrorResponseWriter errorWriter
    ) throws Exception {
        HttpSessionCsrfTokenRepository csrfTokenRepository =
                new HttpSessionCsrfTokenRepository();
        csrfTokenRepository.setHeaderName("X-CSRF-TOKEN");

        http
                // SPA가 JSON API를 사용하므로 Spring 기본 HTML login과 Basic 인증은 열지 않는다.
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                .requestCache(cache -> cache.disable())
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .csrf(csrf -> csrf.csrfTokenRepository(csrfTokenRepository))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, exception) ->
                                errorWriter.write(
                                        request,
                                        response,
                                        ErrorCode.AUTH_UNAUTHENTICATED
                                ))
                        .accessDeniedHandler((request, response, exception) -> {
                            if (exception instanceof CsrfException) {
                                errorWriter.write(
                                        request,
                                        response,
                                        ErrorCode.AUTH_FORBIDDEN,
                                        "요청 검증에 실패했습니다."
                                );
                                return;
                            }
                            errorWriter.write(request, response, ErrorCode.AUTH_FORBIDDEN);
                        }))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.GET,
                                "/api/health",
                                "/actuator/health",
                                "/actuator/health/**",
                                "/api/v1/system/health",
                                "/api/v1/system/info",
                                "/api/v1/auth/csrf-token")
                        .permitAll()
                        // 실제 login controller가 추가돼도 기본 form login을 우회하지 않는다.
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/login")
                        .permitAll()
                        .requestMatchers("/error")
                        .permitAll()
                        // 명시하지 않은 API는 새 기능이 추가돼도 자동 공개되지 않는다.
                        .anyRequest()
                        .authenticated());

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        Map<String, PasswordEncoder> encoders = new HashMap<>();
        encoders.put("argon2", Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8());
        encoders.put("bcrypt", new BCryptPasswordEncoder());

        // 신규 hash는 Argon2id로 만들고 {id} prefix로 기존 bcrypt 검증·점진 재해시를 지원한다.
        return new DelegatingPasswordEncoder("argon2", encoders);
    }

    @Bean
    public UserDetailsService userDetailsService() {
        // DB 인증 구현 전 Spring Boot가 임의의 기본 사용자·비밀번호를 만들지 못하게 한다.
        return username -> {
            throw new UsernameNotFoundException("Authentication failed");
        };
    }
}
