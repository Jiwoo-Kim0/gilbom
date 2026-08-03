package com.gilbom.security.config;

import java.util.function.Supplier;

import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestHandler;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * BREACH masking을 유지하면서 공격자가 보낸 malformed XOR token을 일반 CSRF
 * 불일치로 정규화한다.
 */
public final class SafeXorCsrfTokenRequestHandler
        implements CsrfTokenRequestHandler {

    private final XorCsrfTokenRequestAttributeHandler delegate =
            new XorCsrfTokenRequestAttributeHandler();

    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            Supplier<CsrfToken> csrfToken
    ) {
        delegate.handle(request, response, csrfToken);
    }

    @Override
    public String resolveCsrfTokenValue(
            HttpServletRequest request,
            CsrfToken csrfToken
    ) {
        try {
            return delegate.resolveCsrfTokenValue(request, csrfToken);
        } catch (IllegalArgumentException malformedToken) {
            return null;
        }
    }
}
