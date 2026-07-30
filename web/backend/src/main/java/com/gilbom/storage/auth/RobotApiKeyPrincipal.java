package com.gilbom.storage.auth;

import java.util.UUID;

/**
 * X-API-Key로 인증된 로봇.
 *
 * <p>키 원문은 담지 않는다. {@code keyPrefix}만 로그·감사에 남길 수 있다.
 */
public record RobotApiKeyPrincipal(
        UUID apiKeyId,
        UUID robotId,
        String keyPrefix
) {

    @Override
    public String toString() {
        // 실수로 로그에 찍혀도 식별자 이상은 남지 않게 한다.
        return "RobotApiKeyPrincipal[robotId=" + robotId
                + ", keyPrefix=" + keyPrefix + "]";
    }
}
