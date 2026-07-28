package com.gilbom.audit;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.gilbom.common.error.BusinessException;
import com.gilbom.common.error.ErrorCode;

@Service
public class AuditLogQueryService {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(AuditLogQueryService.class);
    private static final Set<String> VIEW_SNAPSHOT_FIELDS = Set.of(
            "page",
            "size",
            "resultCount",
            "filters"
    );

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final AuditLogService auditLogService;

    public AuditLogQueryService(
            NamedParameterJdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            AuditLogService auditLogService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.auditLogService = auditLogService;
    }

    @Transactional(readOnly = true)
    public AuditLogListResponse find(
            AuditLogFilter filter,
            UUID actorUserId,
            AuditRequestContext requestContext
    ) {
        if (filter.createdFrom() != null
                && filter.createdTo() != null
                && filter.createdFrom().isAfter(filter.createdTo())) {
            throw new BusinessException(ErrorCode.COMMON_INVALID_REQUEST);
        }
        QueryParts query = queryParts(filter);
        Long total = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM audit.audit_log
                WHERE 1 = 1
                """ + query.whereClause(),
                query.parameters(),
                Long.class);

        query.parameters()
                .addValue("size", filter.size())
                .addValue(
                        "offset",
                        Math.multiplyExact(
                                (long) filter.page(),
                                filter.size()
                        )
                );
        List<AuditLogItemResponse> content = jdbcTemplate.query("""
                SELECT
                    audit_id,
                    request_id,
                    actor_user_id,
                    actor_type,
                    action_type,
                    target_type,
                    target_id,
                    before_value,
                    after_value,
                    reason,
                    result,
                    host(ip_address) AS ip_address,
                    user_agent,
                    created_at
                FROM audit.audit_log
                WHERE 1 = 1
                """ + query.whereClause() + """
                ORDER BY created_at DESC, audit_id DESC
                LIMIT :size
                OFFSET :offset
                """,
                query.parameters(),
                (resultSet, rowNumber) -> mapRow(resultSet));

        long totalElements = total == null ? 0 : total;
        AuditLogListResponse response = new AuditLogListResponse(
                content,
                filter.page(),
                filter.size(),
                totalElements,
                Math.toIntExact(Math.ceilDiv(totalElements, filter.size()))
        );
        recordViewed(
                filter,
                actorUserId,
                requestContext,
                content.size(),
                query.filterNames()
        );
        return response;
    }

    private QueryParts queryParts(AuditLogFilter filter) {
        StringBuilder where = new StringBuilder();
        MapSqlParameterSource parameters = new MapSqlParameterSource();
        Set<String> filterNames = new LinkedHashSet<>();

        add(where, parameters, filterNames, "requestId",
                " AND request_id = :requestId",
                filter.requestId());
        add(where, parameters, filterNames, "actorUserId",
                " AND actor_user_id = :actorUserId",
                filter.actorUserId());
        add(where, parameters, filterNames, "actionType",
                " AND action_type = :actionType",
                filter.actionType());
        add(where, parameters, filterNames, "targetType",
                " AND target_type = :targetType",
                filter.targetType());
        add(where, parameters, filterNames, "targetId",
                " AND target_id = :targetId",
                filter.targetId());
        add(where, parameters, filterNames, "result",
                " AND result = :result",
                filter.result() == null ? null : filter.result().name());
        add(where, parameters, filterNames, "createdFrom",
                " AND created_at >= :createdFrom",
                filter.createdFrom());
        add(where, parameters, filterNames, "createdTo",
                " AND created_at <= :createdTo",
                filter.createdTo());
        return new QueryParts(
                where.toString(),
                parameters,
                List.copyOf(filterNames)
        );
    }

    private static void add(
            StringBuilder where,
            MapSqlParameterSource parameters,
            Set<String> filterNames,
            String parameterName,
            String predicate,
            Object value
    ) {
        if (value == null) {
            return;
        }
        where.append(predicate).append('\n');
        parameters.addValue(parameterName, value);
        filterNames.add(parameterName);
    }

    private AuditLogItemResponse mapRow(ResultSet resultSet)
            throws SQLException {
        Timestamp createdAt = resultSet.getTimestamp("created_at");
        return new AuditLogItemResponse(
                resultSet.getLong("audit_id"),
                resultSet.getObject("request_id", UUID.class),
                resultSet.getObject("actor_user_id", UUID.class),
                resultSet.getString("actor_type"),
                resultSet.getString("action_type"),
                resultSet.getString("target_type"),
                resultSet.getObject("target_id", UUID.class),
                json(resultSet.getString("before_value")),
                json(resultSet.getString("after_value")),
                resultSet.getString("reason"),
                resultSet.getString("result"),
                resultSet.getString("ip_address"),
                resultSet.getString("user_agent"),
                createdAt.toInstant()
        );
    }

    private JsonNode json(String value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Stored audit snapshot is invalid"
            );
        }
    }

    private void recordViewed(
            AuditLogFilter filter,
            UUID actorUserId,
            AuditRequestContext requestContext,
            int resultCount,
            List<String> filterNames
    ) {
        try {
            auditLogService.appendBestEffort(new AuditLogRecord(
                    requestContext,
                    actorUserId,
                    AuditActorType.USER,
                    AuditAction.AUDIT_LOG_VIEWED.databaseValue(),
                    AuditTargetType.AUDIT_LOG,
                    null,
                    null,
                    AuditSnapshot.allow(
                            VIEW_SNAPSHOT_FIELDS,
                            Map.of(
                                    "page", filter.page(),
                                    "size", filter.size(),
                                    "resultCount", resultCount,
                                    "filters", filterNames
                            )
                    ),
                    null,
                    AuditResult.SUCCESS
            ));
        } catch (RuntimeException exception) {
            LOGGER.error(
                    "Audit log view audit was unavailable. requestId={}",
                    requestContext.requestId()
            );
        }
    }

    private record QueryParts(
            String whereClause,
            MapSqlParameterSource parameters,
            List<String> filterNames
    ) {
    }
}
