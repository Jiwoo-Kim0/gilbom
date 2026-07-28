package com.gilbom.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

class ApiPreAuthorizationContractTest {

    private static final Set<String> PUBLIC_OR_AUTHENTICATION_FLOW_CONTROLLERS =
            Set.of(
                    "com.gilbom.api.HealthController",
                    "com.gilbom.auth.login.AuthenticationLoginController",
                    "com.gilbom.auth.logout.AuthenticationLogoutController",
                    "com.gilbom.auth.mfa.MfaAuthenticationController",
                    "com.gilbom.auth.registration.PublicRegistrationController",
                    "com.gilbom.auth.session.AuthenticationSessionStateController",
                    "com.gilbom.security.web.CsrfTokenController"
            );

    @Test
    void everyProtectedApiDeclaresMethodAuthorization() throws Exception {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(
                new AnnotationTypeFilter(RestController.class)
        );

        for (var component : scanner.findCandidateComponents("com.gilbom")) {
            String className = component.getBeanClassName();
            if (PUBLIC_OR_AUTHENTICATION_FLOW_CONTROLLERS.contains(className)) {
                continue;
            }

            Class<?> controller = Class.forName(className);
            if (controller.getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                    .getPath()
                    .contains("/test/")) {
                continue;
            }
            boolean classProtected = AnnotatedElementUtils.hasAnnotation(
                    controller,
                    PreAuthorize.class
            );
            for (Method method : controller.getDeclaredMethods()) {
                if (!AnnotatedElementUtils.hasAnnotation(
                        method,
                        RequestMapping.class
                )) {
                    continue;
                }
                assertThat(
                        classProtected || AnnotatedElementUtils.hasAnnotation(
                                method,
                                PreAuthorize.class
                        )
                ).as("%s#%s must declare @PreAuthorize",
                        className,
                        method.getName())
                        .isTrue();
            }
        }
    }
}
