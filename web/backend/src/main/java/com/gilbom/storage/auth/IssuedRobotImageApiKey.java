package com.gilbom.storage.auth;

import java.util.UUID;

public record IssuedRobotImageApiKey(
        UUID apiKeyId,
        String uploadUrl,
        String apiKey,
        String keyPrefix
) {
}
