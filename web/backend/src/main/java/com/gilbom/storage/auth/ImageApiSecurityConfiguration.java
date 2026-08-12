package com.gilbom.storage.auth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.session.NullAuthenticatedSessionStrategy;
import org.springframework.security.web.context.NullSecurityContextRepository;

import com.gilbom.common.error.ErrorCode;
import com.gilbom.security.web.SecurityErrorResponseWriter;
import com.gilbom.storage.url.ImageUrlSigner;

/**
 * 이미지 API 전용 보안 체인이다.
 *
 * <p>{@code SecurityConfig}의 관리자 체인은 세션 쿠키와 CSRF를 전제로 한다. 반면
 * authorization-matrix.md 279~283행은 이미지 API를 로봇 API Key로 인증하고 CSRF
 * 대상에서 제외하도록 정의한다. 성질이 다른 두 인증을 한 체인에 넣으면 서로의
 * 예외 조건이 되므로, 공용 설정을 고치는 대신 이 경로만 담당하는 체인을 따로 둔다.
 *
 * <p>{@code securityMatcher}로 {@code /api/v1/images/**}만 가져가고 나머지는 기존
 * 체인이 그대로 처리한다. 순서를 앞에 두어야 이 경로에서 이 체인이 선택된다.
 */
@Configuration
public class ImageApiSecurityConfiguration {

    static final String IMAGE_API_PATTERN = "/api/v1/images/**";

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 10)
    public SecurityFilterChain imageApiSecurityFilterChain(
            HttpSecurity http,
            SecurityErrorResponseWriter errorWriter,
            RobotApiKeyRepository robotApiKeyRepository,
            ImageUrlSigner imageUrlSigner
    ) throws Exception {
        http
                .securityMatcher(IMAGE_API_PATTERN)
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                .requestCache(cache -> cache.disable())
                .anonymous(anonymous -> anonymous.disable())
                // 로봇 클라이언트와 <img> 요청은 브라우저 폼이 아니므로 CSRF 대상이 아니다.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                        // 서명·API Key 인증은 로그인 성공이 아니므로 기존 관리자
                        // 세션 ID를 회전시키는 후처리를 실행하지 않는다.
                        .sessionAuthenticationStrategy(
                                new NullAuthenticatedSessionStrategy()))
                // 이 체인의 인증은 요청마다 자격증명에서 새로 만든다. 세션에 저장하지 않는다.
                .securityContext(context -> context
                        .securityContextRepository(
                                new NullSecurityContextRepository()))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, exception) ->
                                errorWriter.write(
                                        request,
                                        response,
                                        ErrorCode.AUTH_UNAUTHENTICATED))
                        .accessDeniedHandler((request, response, exception) ->
                                errorWriter.write(
                                        request,
                                        response,
                                        ErrorCode.AUTH_FORBIDDEN)))
                .authorizeHttpRequests(authorize -> authorize
                        .anyRequest()
                        .authenticated())
                .addFilterBefore(
                        new RobotApiKeyAuthenticationFilter(
                                robotApiKeyRepository),
                        UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(
                        new SignedImageUrlAuthenticationFilter(imageUrlSigner),
                        UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
