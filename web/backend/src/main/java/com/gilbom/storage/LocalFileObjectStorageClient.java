package com.gilbom.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.gilbom.common.error.BusinessException;
import com.gilbom.common.error.ErrorCode;

/**
 * 이미지 바이트를 서버 로컬 디스크에 두는 어댑터다.
 *
 * <p>팀이 외부 오브젝트 스토리지를 쓰지 않기로 정해(2026-08-01) <b>이것이 유일한
 * 구현</b>이다. 업로드·조회·LLM 입력이 모두 이 위에서 완결된다.
 *
 * <p>이 어댑터가 쓴 행은 bucket_name이 {@code local-disk}로 남는다. 값이 한 종류뿐
 * 이어도 읽기가 bucket으로 구현을 고르는 구조는 유지한다. 그래야 나중에 저장소가
 * 하나 더 생겨도 이미 올라간 행을 옮기지 않아도 된다.
 *
 * <p><b>운영 주의.</b> 이 경로는 반드시 영속 volume이어야 한다. 복제본이 없으므로
 * 디스크를 잃으면 이미지도 잃는다. DB의 {@code storage.robot_image}와 항상 같이
 * 백업해야 한다. 한쪽만 남으면 메타데이터는 있는데 파일이 없는 상태가 된다.
 */
@Component
public class LocalFileObjectStorageClient implements ObjectStorageClient {

    static final String BUCKET_NAME = "local-disk";

    /**
     * object key는 서버가 만들지만, 경로 조작이 가능한 값이 저장소 경로로 쓰이는 일이
     * 없도록 읽기·쓰기 양쪽에서 다시 검사한다.
     */
    private static final Pattern SAFE_OBJECT_KEY =
            Pattern.compile("^[A-Za-z0-9][A-Za-z0-9/_.-]{0,499}$");

    private static final Logger log =
            LoggerFactory.getLogger(LocalFileObjectStorageClient.class);

    private final Path rootDirectory;

    public LocalFileObjectStorageClient(StorageProperties properties) {
        this.rootDirectory = Path.of(properties.getLocalRootDirectory())
                .toAbsolutePath()
                .normalize();
        warnIfNotWritable();
    }

    /**
     * 저장 경로를 쓸 수 없으면 기동 시점에 알린다.
     *
     * <p>컨테이너의 작업 디렉터리는 보통 root 소유이고 애플리케이션은 비특권 사용자로
     * 돈다. 그 상태에서는 첫 업로드가 503으로 실패하는데, 로봇 쪽 로그만 보면 저장소
     * 장애와 구분되지 않는다. 실제로 로컬 compose에서 이 상황이 나왔다.
     *
     * <p>기동을 중단시키지는 않는다. 이미지 업로드는 관제·결함 조회와 독립적이라,
     * 경로 설정 하나 때문에 서버 전체가 뜨지 않는 편이 더 나쁘다.
     */
    private void warnIfNotWritable() {
        try {
            Files.createDirectories(rootDirectory);
        } catch (IOException exception) {
            log.warn("Image storage root {} cannot be created: uploads will "
                            + "fail with 503. Mount a writable volume and set "
                            + "GILBOM_STORAGE_LOCAL_ROOT_DIRECTORY.",
                    rootDirectory,
                    exception);
            return;
        }
        if (!Files.isWritable(rootDirectory)) {
            log.warn("Image storage root {} is not writable: uploads will "
                            + "fail with 503. Mount a writable volume and set "
                            + "GILBOM_STORAGE_LOCAL_ROOT_DIRECTORY.",
                    rootDirectory);
        }
    }

    @Override
    public String bucketName() {
        return BUCKET_NAME;
    }

    @Override
    public boolean supports(String bucketName) {
        return BUCKET_NAME.equals(bucketName);
    }

    @Override
    public void put(String objectKey, byte[] content, String contentType) {
        Path target = resolve(objectKey);
        try {
            Files.createDirectories(target.getParent());
            // 같은 object key로 두 번 쓰는 경우는 멱등 재업로드뿐이라 덮어써도 안전하다.
            Files.write(target, content);
        } catch (IOException exception) {
            throw new BusinessException(ErrorCode.S3_UNAVAILABLE, exception);
        }
    }

    @Override
    public byte[] read(String bucketName, String objectKey) {
        if (!supports(bucketName)) {
            throw new BusinessException(ErrorCode.S3_UNAVAILABLE);
        }
        Path target = resolve(objectKey);
        if (!Files.isRegularFile(target)) {
            // DB에는 행이 있는데 파일이 없는 상태다. 저장소 장애와 구분해서 알린다.
            throw new BusinessException(ErrorCode.IMAGE_NOT_FOUND);
        }
        try {
            return Files.readAllBytes(target);
        } catch (IOException exception) {
            throw new BusinessException(ErrorCode.S3_UNAVAILABLE, exception);
        }
    }

    private Path resolve(String objectKey) {
        if (objectKey == null || !SAFE_OBJECT_KEY.matcher(objectKey).matches()) {
            throw new BusinessException(ErrorCode.COMMON_INVALID_REQUEST);
        }
        Path resolved = rootDirectory.resolve(objectKey).normalize();
        if (!resolved.startsWith(rootDirectory)) {
            throw new BusinessException(ErrorCode.COMMON_INVALID_REQUEST);
        }
        return resolved;
    }
}
