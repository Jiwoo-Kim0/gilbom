package com.gilbom.security;

import java.util.Map;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/test/security")
public class SecurityTestController {

    @GetMapping("/protected")
    Map<String, String> protectedResource() {
        return Map.of("result", "authenticated");
    }

    @PostMapping("/mutate")
    Map<String, String> mutate() {
        return Map.of("result", "changed");
    }

    @GetMapping("/admin")
    @PreAuthorize("hasAuthority('ADMIN_SETTINGS_MANAGE')")
    Map<String, String> adminOnly() {
        return Map.of("result", "authorized");
    }
}
