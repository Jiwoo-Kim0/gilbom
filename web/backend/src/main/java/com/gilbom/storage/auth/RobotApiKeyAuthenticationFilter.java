package com.gilbom.storage.auth;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * {@code X-API-Key}로 로봇을 인증한다.
 *
 * <p>실패해도 여기서 응답을 쓰지 않고 인증을 세우지 않은 채 넘긴다. 그러면 체인의
 * authenticationEntryPoint가 401을 쓰므로, "키 없음"과 "키 틀림"이 같은 응답이 되어
 * 키 존재 여부를 알아내는 데 쓸 수 없다.
 */
public class RobotApiKeyAuthenticationFilter extends OncePerRequestFilter {

    static final String HEADER_NAME = "X-API-Key";
    static final String UPLOAD_PATH = "/api/v1/images";

    private final RobotApiKeyRepository repository;

    public RobotApiKeyAuthenticationFilter(RobotApiKeyRepository repository) {
        this.repository = repository;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !(HttpMethod.POST.matches(request.getMethod())
                && UPLOAD_PATH.equals(request.getRequestURI()));
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        authenticate(request.getHeader(HEADER_NAME))
                .ifPresent(RobotApiKeyAuthenticationFilter::setAuthentication);
        filterChain.doFilter(request, response);
    }

    private Optional<RobotApiKeyPrincipal> authenticate(String presentedKey) {
        if (presentedKey == null || presentedKey.isBlank()) {
            return Optional.empty();
        }
        return repository.findActiveByHash(sha256Hex(presentedKey.trim()));
    }

    private static void setAuthentication(RobotApiKeyPrincipal principal) {
        UsernamePasswordAuthenticationToken authentication =
                UsernamePasswordAuthenticationToken.authenticated(
                        principal,
                        null,
                        List.of(new SimpleGrantedAuthority(
                                ImageApiAuthorities.ROBOT_IMAGE_UPLOAD)));
        SecurityContext context =
                SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
    }

    /**
     * 키는 서버가 만든 32바이트 난수라 사전 공격 대상이 아니므로 SHA-256으로 충분하다.
     * 조회는 해시 컬럼의 unique index로 하고, 비교 자체는 DB가 수행한다.
     */
    private static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }
}
