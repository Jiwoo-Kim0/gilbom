package com.gilbom.report.realtime;

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

@RestController
@RequestMapping("/api/v1/stream")
public class ReportSseController {

    private final ReportSseService sseService;
    private final AuthenticatedUserIdResolver userIdResolver;

    public ReportSseController(
            ReportSseService sseService,
            AuthenticatedUserIdResolver userIdResolver
    ) {
        this.sseService = sseService;
        this.userIdResolver = userIdResolver;
    }

    @GetMapping(
            value = "/reports",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("hasAuthority('REPORT_VIEW')")
    public ResponseEntity<SseEmitter> subscribe(
            @RequestParam(required = false) UUID reportId,
            Authentication authentication,
            HttpServletRequest request
    ) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            throw new BusinessException(
                    ErrorCode.AUTH_UNAUTHENTICATED);
        }
        SseEmitter emitter = sseService.subscribe(
                userIdResolver.require(authentication),
                session.getId(),
                reportId);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(emitter);
    }
}
