package com.gilbom.auth.my;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.gilbom.auth.mfa.RoleSummaryResponse;

public record MyProfileResponse(
        UUID userId,
        String email,
        String displayName,
        String departmentName,
        String jobTitle,
        String officeName,
        List<RoleSummaryResponse> roles,
        List<String> permissions,
        MfaProfileStatus mfaStatus,
        Instant passwordChangedAt,
        Instant lastLoginAt
) {
}
