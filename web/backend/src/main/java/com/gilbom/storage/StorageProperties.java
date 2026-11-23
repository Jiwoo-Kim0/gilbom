package com.gilbom.storage;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 결함 증거 이미지 저장소 설정이다.
 *
 * <p>기본값을 코드에 두고 application.yml에는 항목을 만들지 않는다. Spring Boot의
 * relaxed binding이 {@code GILBOM_STORAGE_LOCAL_ROOT_DIRECTORY} 같은 환경변수를
 * 그대로 바인딩하므로, 환경별 값은 배포 환경변수로만 주입하면 된다.
 *
 * <p>저장소는 로컬 디스크로 확정이다(2026-08-01). 항목이 로컬 경로와 URL 서명에만
 * 몰려 있는 것은 그 때문이다.
 */
@Component
@ConfigurationProperties(prefix = "gilbom.storage")
public class StorageProperties {

    /**
     * 로컬 디스크 어댑터가 객체를 쓰는 최상위 경로.
     *
     * <p>컨테이너에서는 반드시 volume으로 마운트해야 한다. 마운트하지 않으면
     * 재배포 때 업로드된 이미지가 사라진다.
     */
    private String localRootDirectory = "./data/robot-images";

    /**
     * Jetson에 전달할 이미지 업로드 전체 URL.
     */
    private String uploadUrl = "http://localhost:3000/api/v1/images";

    /**
     * 발급한 이미지 조회 URL의 유효 시간.
     */
    private Duration urlTtl = Duration.ofMinutes(5);

    /**
     * 조회 URL 서명 키(Base64, 32바이트 이상 권장).
     *
     * <p>비워 두면 기동 시 임의 키를 생성한다. 개발 편의를 위한 것이며 재기동하면
     * 이전에 발급한 URL이 모두 무효가 되고, 인스턴스가 여럿이면 서로의 URL을
     * 검증하지 못한다. 운영에서는 반드시 외부에서 주입한다.
     */
    private String urlSigningKeyBase64 = "";

    public String getLocalRootDirectory() {
        return localRootDirectory;
    }

    public void setLocalRootDirectory(String localRootDirectory) {
        this.localRootDirectory = localRootDirectory;
    }

    public String getUploadUrl() {
        return uploadUrl;
    }

    public void setUploadUrl(String uploadUrl) {
        this.uploadUrl = uploadUrl;
    }

    public Duration getUrlTtl() {
        return urlTtl;
    }

    public void setUrlTtl(Duration urlTtl) {
        this.urlTtl = urlTtl;
    }

    public String getUrlSigningKeyBase64() {
        return urlSigningKeyBase64;
    }

    public void setUrlSigningKeyBase64(String urlSigningKeyBase64) {
        this.urlSigningKeyBase64 =
                urlSigningKeyBase64 == null ? "" : urlSigningKeyBase64;
    }
}
