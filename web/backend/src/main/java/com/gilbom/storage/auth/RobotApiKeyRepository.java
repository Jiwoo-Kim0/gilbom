package com.gilbom.storage.auth;

import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 로봇 이미지 업로드 API Key 조회.
 */
@Repository
public class RobotApiKeyRepository {

    private final JdbcTemplate jdbcTemplate;

    public RobotApiKeyRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 해시로 유효한 키를 찾는다. 상태와 만료를 질의 조건에 넣어, 호출부가 검사를
     * 빠뜨려도 만료·회수된 키가 통과할 수 없게 한다.
     */
    @Transactional(readOnly = true)
    public Optional<RobotApiKeyPrincipal> findActiveByHash(String keyHash) {
        return Optional.ofNullable(jdbcTemplate.query("""
                        SELECT api_key_id, robot_id, key_prefix
                        FROM storage.robot_image_api_key
                        WHERE key_hash = ?
                          AND key_status = 'ACTIVE'
                          AND (expires_at IS NULL OR expires_at > NOW())
                        """,
                resultSet -> resultSet.next()
                        ? new RobotApiKeyPrincipal(
                                resultSet.getObject("api_key_id", UUID.class),
                                resultSet.getObject("robot_id", UUID.class),
                                resultSet.getString("key_prefix"))
                        : null,
                keyHash));
    }

    /**
     * 업로드가 성공한 트랜잭션에서만 호출한다. 인증 필터에서 매 요청 갱신하면
     * 읽기 경로가 쓰기 트랜잭션이 된다.
     */
    @Transactional
    public void touchLastUsedAt(UUID apiKeyId) {
        jdbcTemplate.update("""
                UPDATE storage.robot_image_api_key
                SET last_used_at = NOW()
                WHERE api_key_id = ?
                """,
                apiKeyId);
    }
}
