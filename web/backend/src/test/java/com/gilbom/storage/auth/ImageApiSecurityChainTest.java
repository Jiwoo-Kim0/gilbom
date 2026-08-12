package com.gilbom.storage.auth;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.gilbom.api.HealthController;
import com.gilbom.audit.SecurityAuditService;
import com.gilbom.auth.session.AuthenticationSessionService;
import com.gilbom.auth.session.SessionSecurityStateRepository;
import com.gilbom.common.web.RequestIdFilter;
import com.gilbom.security.config.SecurityConfig;
import com.gilbom.security.web.SecurityErrorResponseWriter;
import com.gilbom.security.web.SecurityHeadersFilter;
import com.gilbom.storage.ImageBytesReader;
import com.gilbom.storage.ImageBytesReader.ImageBytes;
import com.gilbom.storage.ImageUploadService;
import com.gilbom.storage.ImageUploadService.StoredImageSummary;
import com.gilbom.storage.ImageUploadService.UploadOutcome;
import com.gilbom.storage.StorageProperties;
import com.gilbom.storage.api.ImageContentController;
import com.gilbom.storage.api.ImageUploadController;
import com.gilbom.storage.url.ImageUrlSigner;

/**
 * 이미지 전용 보안 체인이 관리자 체인과 한 컨텍스트에서 공존하는지 검증한다.
 *
 * <p>두 체인은 인증 방식이 반대다. 관리자 체인은 세션과 CSRF를 요구하고, 이미지
 * 체인은 API Key·서명 URL만 본다. 경로 분리가 깨지면 로봇 업로드가 CSRF에 막히거나
 * 반대로 관리자 API가 무인증으로 열리므로, 두 방향을 모두 확인한다.
 */
@WebMvcTest(controllers = {
        ImageUploadController.class,
        ImageContentController.class,
        HealthController.class
})
@Import({
        SecurityConfig.class,
        ImageApiSecurityConfiguration.class,
        SecurityErrorResponseWriter.class,
        SecurityHeadersFilter.class,
        RequestIdFilter.class,
        StorageProperties.class,
        ImageUrlSigner.class
})
class ImageApiSecurityChainTest {

    private static final UUID ROBOT_ID =
            UUID.fromString("8750afcb-d682-4ef6-a90d-f63fcf5c2151");
    private static final UUID EVENT_ID =
            UUID.fromString("9a1e0000-0000-4000-8000-000000000051");
    private static final UUID IMAGE_ID =
            UUID.fromString("7c1e0000-0000-4000-8000-000000000051");
    private static final UUID API_KEY_ID =
            UUID.fromString("6b1e0000-0000-4000-8000-000000000051");
    private static final String API_KEY = "gik_chain-test-key";
    /** 필터가 조회에 사용하는 해시. 상수로 박으면 해시 규약이 바뀌어도 테스트가 통과한다. */
    private static final String API_KEY_HASH = sha256Hex(API_KEY);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ImageUploadService uploadService;

    @MockitoBean
    private ImageBytesReader imageBytesReader;

    @MockitoBean
    private RobotApiKeyRepository apiKeyRepository;

    @MockitoBean
    private SessionSecurityStateRepository sessionStateRepository;

    @MockitoBean
    private AuthenticationSessionService authenticationSessionService;

    @MockitoBean
    private SecurityAuditService securityAuditService;

    @Autowired
    private ImageUrlSigner imageUrlSigner;

    @Test
    void rejectsUploadWithoutAnApiKey() throws Exception {
        mockMvc.perform(multipart("/api/v1/images")
                        .file(jpegPart())
                        .param("event_id", EVENT_ID.toString())
                        .param("robot_id", ROBOT_ID.toString()))
                // CSRF 토큰 없이도 401이어야 한다. 403이면 관리자 체인이 가로챈 것이다.
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_UNAUTHENTICATED"));
    }

    @Test
    void rejectsUploadWithAnUnknownApiKey() throws Exception {
        when(apiKeyRepository.findActiveByHash(any()))
                .thenReturn(Optional.empty());

        mockMvc.perform(multipart("/api/v1/images")
                        .file(jpegPart())
                        .param("event_id", EVENT_ID.toString())
                        .param("robot_id", ROBOT_ID.toString())
                        .header("X-API-Key", "gik_wrong-key"))
                // 키 없음과 같은 응답이어야 키 존재 여부를 알아낼 수 없다.
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_UNAUTHENTICATED"));
    }

    @Test
    void acceptsUploadWithAValidApiKeyAndNoCsrfToken() throws Exception {
        when(apiKeyRepository.findActiveByHash(API_KEY_HASH))
                .thenReturn(Optional.of(new RobotApiKeyPrincipal(
                        API_KEY_ID, ROBOT_ID, "gik_chain-te")));
        when(uploadService.upload(any(), any(), any(), any(), any()))
                .thenReturn(new UploadOutcome(
                        new StoredImageSummary(
                                IMAGE_ID, EVENT_ID, "robots/key.jpg", 64, 48),
                        true));

        mockMvc.perform(multipart("/api/v1/images")
                        .file(jpegPart())
                        .param("event_id", EVENT_ID.toString())
                        .param("robot_id", ROBOT_ID.toString())
                        .header("X-API-Key", API_KEY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.imageId").value(IMAGE_ID.toString()));
    }

    @Test
    void rejectsImageContentWithoutASignedToken() throws Exception {
        mockMvc.perform(get("/api/v1/images/" + IMAGE_ID + "/content"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsImageContentWithATamperedToken() throws Exception {
        mockMvc.perform(get("/api/v1/images/" + IMAGE_ID + "/content")
                        .param("token", "99999999999.forged"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void servesImageContentForASignedToken() throws Exception {
        when(imageBytesReader.read(IMAGE_ID)).thenReturn(new ImageBytes(
                IMAGE_ID, new byte[] {1, 2, 3}, "image/jpeg", 64, 48));
        String url = imageUrlSigner.issue(IMAGE_ID).url();

        mockMvc.perform(get(url))
                .andExpect(status().isOk())
                .andExpect(result -> {
                    String cacheControl =
                            result.getResponse().getHeader("Cache-Control");
                    if (cacheControl == null
                            || !cacheControl.contains("no-store")) {
                        throw new AssertionError(
                                "만료가 짧은 비공개 이미지는 캐시에 남기지 않아야 한다: "
                                        + cacheControl);
                    }
                });
    }

    @Test
    void signedImageAuthenticationDoesNotRotateAnExistingLoginSession()
            throws Exception {
        when(imageBytesReader.read(IMAGE_ID)).thenReturn(new ImageBytes(
                IMAGE_ID, new byte[] {1, 2, 3}, "image/jpeg", 64, 48));
        String url = imageUrlSigner.issue(IMAGE_ID).url();
        MockHttpSession loginSession = authenticatedLoginSession();
        String originalSessionId = loginSession.getId();
        Object originalLoginContext = loginSession.getAttribute(
                HttpSessionSecurityContextRepository
                        .SPRING_SECURITY_CONTEXT_KEY);

        mockMvc.perform(get(url).session(loginSession))
                .andExpect(status().isOk())
                .andExpect(result -> {
                    if (!originalSessionId.equals(loginSession.getId())) {
                        throw new AssertionError(
                                "이미지 인증이 기존 로그인 세션 ID를 회전했습니다");
                    }
                })
                .andExpect(result -> {
                    Object storedLoginContext = loginSession.getAttribute(
                            HttpSessionSecurityContextRepository
                                    .SPRING_SECURITY_CONTEXT_KEY);
                    if (storedLoginContext != originalLoginContext) {
                        throw new AssertionError(
                                "이미지 인증이 기존 로그인 인증정보를 덮어썼습니다");
                    }
                })
                .andExpect(result -> {
                    if (result.getResponse().getHeader(
                            HttpHeaders.SET_COOKIE) != null) {
                        throw new AssertionError(
                                "이미지 응답이 로그인 세션 쿠키를 변경했습니다");
                    }
                });
    }

    @Test
    void leavesNonImagePathsToTheAdministratorChain() throws Exception {
        // 이미지 체인이 경로를 넘겨받지 않았음을 확인한다. 헬스는 관리자 체인이 공개한다.
        mockMvc.perform(get("/api/health")).andExpect(status().isOk());
    }

    private static MockHttpSession authenticatedLoginSession() {
        MockHttpSession session = new MockHttpSession();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(
                        UUID.fromString(
                                "17000000-0000-4000-8000-000000000001"),
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        session.setAttribute(
                HttpSessionSecurityContextRepository
                        .SPRING_SECURITY_CONTEXT_KEY,
                context);
        return session;
    }

    private static String sha256Hex(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(
                                    java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static MockMultipartFile jpegPart() {
        return new MockMultipartFile(
                "file",
                "evidence.jpg",
                "image/jpeg",
                new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x00});
    }
}
