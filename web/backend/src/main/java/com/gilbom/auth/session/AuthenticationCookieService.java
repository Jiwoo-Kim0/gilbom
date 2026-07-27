package com.gilbom.auth.session;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;

import jakarta.servlet.http.HttpServletResponse;

@Service
public class AuthenticationCookieService {

    private final String cookieName;
    private final boolean httpOnly;
    private final boolean secure;
    private final String sameSite;
    private final String path;

    public AuthenticationCookieService(
            @Value("${server.servlet.session.cookie.name:GILBOM_SESSION}")
            String cookieName,
            @Value("${server.servlet.session.cookie.http-only:true}")
            boolean httpOnly,
            @Value("${server.servlet.session.cookie.secure:false}")
            boolean secure,
            @Value("${server.servlet.session.cookie.same-site:lax}")
            String sameSite,
            @Value("${server.servlet.session.cookie.path:/}")
            String path
    ) {
        this.cookieName = cookieName;
        this.httpOnly = httpOnly;
        this.secure = secure;
        this.sameSite = sameSite;
        this.path = path;
    }

    public void expire(HttpServletResponse response) {
        ResponseCookie expired = ResponseCookie.from(cookieName, "")
                .httpOnly(httpOnly)
                .secure(secure)
                .sameSite(sameSite)
                .path(path)
                .maxAge(Duration.ZERO)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, expired.toString());
    }
}
