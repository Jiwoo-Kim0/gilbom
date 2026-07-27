package com.gilbom.auth.mfa;

import java.util.List;
import java.util.UUID;

public record AuthenticatedUserResponse(
        UUID userId,
        String displayName,
        List<RoleSummaryResponse> roles,
        List<String> permissions
) {
}
