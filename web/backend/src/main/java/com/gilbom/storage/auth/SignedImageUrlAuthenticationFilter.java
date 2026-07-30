package com.gilbom.storage.auth;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import com.gilbom.storage.url.ImageUrlSigner;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 서명된 이미지 URL을 검증한다. S3 presigned URL이 하던 일을 로컬 단계에서 대신한다.
 *
 * <p>브라우저 {@code <img src>}는 헤더를 붙일 수 없고, 개발 서버와 운영 Nginx의
 * Origin이 달라 쿠키에 기대기도 어렵다. 그래서 URL 자체가 자격증명이 되며, 세션이
 * 아니라 서명이 접근을 허용한다. 만료는 짧게 둔다.
 *
 * <p>실패 시 응답을 쓰지 않고 인증 없이 넘겨 체인이 401을 쓰게 한다.
 */
public class SignedImageUrlAuthenticationFilter extends OncePerRequestFilter {

    static final String TOKEN_PARAMETER = "token";

    private static final Pattern CONTENT_PATH = Pattern.compile(
            "^/api/v1/images/([0-9a-fA-F-]{36})/content$");

    private final ImageUrlSigner signer;

    public SignedImageUrlAuthenticationFilter(ImageUrlSigner signer) {
        this.signer = signer;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !(HttpMethod.GET.matches(request.getMethod())
                && CONTENT_PATH.matcher(request.getRequestURI()).matches());
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        UUID imageId = extractImageId(request.getRequestURI());
        if (imageId != null
                && signer.verify(
                        imageId,
                        request.getParameter(TOKEN_PARAMETER))) {
            setAuthentication(imageId);
        }
        filterChain.doFilter(request, response);
    }

    private static UUID extractImageId(String requestUri) {
        Matcher matcher = CONTENT_PATH.matcher(requestUri);
        if (!matcher.matches()) {
            return null;
        }
        try {
            return UUID.fromString(matcher.group(1));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static void setAuthentication(UUID imageId) {
        UsernamePasswordAuthenticationToken authentication =
                UsernamePasswordAuthenticationToken.authenticated(
                        imageId,
                        null,
                        List.of(new SimpleGrantedAuthority(
                                ImageApiAuthorities.IMAGE_CONTENT_READ)));
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
    }
}
