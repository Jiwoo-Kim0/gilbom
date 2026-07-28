package com.gilbom.audit;

import java.util.List;

public record AuditLogListResponse(
        List<AuditLogItemResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {

    public AuditLogListResponse {
        content = List.copyOf(content);
    }
}
