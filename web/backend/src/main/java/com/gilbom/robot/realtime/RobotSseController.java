package com.gilbom.robot.realtime;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.gilbom.auth.AuthenticatedUserIdResolver;
import com.gilbom.common.error.BusinessException;
import com.gilbom.common.error.ErrorCode;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

@RestController
@RequestMapping("/api/v1/stream")
public class RobotSseController {

    private final RobotSseService sseService;
    private final AuthenticatedUserIdResolver userIdResolver;

    public RobotSseController(
            RobotSseService sseService,
            AuthenticatedUserIdResolver userIdResolver
    ) {
        this.sseService = sseService;
        this.userIdResolver = userIdResolver;
    }

    @GetMapping(
            value = "/robots",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("hasAuthority('ROBOT_STATUS_VIEW')")
    public ResponseEntity<SseEmitter> subscribe(
            Authentication authentication,
            HttpServletRequest request
    ) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            throw new BusinessException(ErrorCode.AUTH_UNAUTHENTICATED);
        }
        SseEmitter emitter = sseService.subscribe(
                userIdResolver.require(authentication),
                session.getId());
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(emitter);
    }
}
