package com.gilbom.defect.realtime;

import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.gilbom.auth.AuthenticatedUserIdResolver;
import com.gilbom.common.error.BusinessException;
import com.gilbom.common.error.ErrorCode;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

/**
 * Swagger 21.16 {@code GET /api/v1/stream/defects}.
 * 이벤트명은 {@code defect.created}, {@code defect.updated}다.
 */
@RestController
@RequestMapping("/api/v1/stream")
public class DefectSseController {

    private final DefectSseService sseService;
    private final AuthenticatedUserIdResolver userIdResolver;

    public DefectSseController(
            DefectSseService sseService,
            AuthenticatedUserIdResolver userIdResolver
    ) {
        this.sseService = sseService;
        this.userIdResolver = userIdResolver;
    }

    @GetMapping(
            value = "/defects",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("hasAuthority('DEFECT_STATUS_VIEW')")
    public ResponseEntity<SseEmitter> subscribe(
            @RequestParam(required = false) UUID patrolAreaId,
            Authentication authentication,
            HttpServletRequest request
    ) {
        // 구독 수명은 세션 수명을 넘지 못한다. 세션이 없으면 스트림을 열지 않는다.
        HttpSession session = request.getSession(false);
        if (session == null) {
            throw new BusinessException(ErrorCode.AUTH_UNAUTHENTICATED);
        }
        SseEmitter emitter = sseService.subscribe(
                userIdResolver.require(authentication),
                session.getId(),
                patrolAreaId);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(emitter);
    }
}
