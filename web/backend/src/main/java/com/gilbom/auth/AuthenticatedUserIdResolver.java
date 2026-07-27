package com.gilbom.auth;

import java.util.UUID;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import com.gilbom.common.error.BusinessException;
import com.gilbom.common.error.ErrorCode;

@Component
public class AuthenticatedUserIdResolver {

    public UUID require(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw unauthenticated();
        }
        String principalName = authentication.getName();
        if (principalName == null || principalName.isBlank()) {
            throw unauthenticated();
        }
        try {
            return UUID.fromString(principalName);
        } catch (IllegalArgumentException exception) {
            throw unauthenticated();
        }
    }

    private static BusinessException unauthenticated() {
        return new BusinessException(ErrorCode.AUTH_UNAUTHENTICATED);
    }
}
