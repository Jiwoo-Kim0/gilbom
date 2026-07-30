package com.gilbom.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.gilbom.common.error.BusinessException;
import com.gilbom.common.error.ErrorCode;

/**
 * S3 버킷이 준비되기 전까지 쓰는 로컬 디스크 어댑터다.
 *
 * <p>업로드·조회·LLM 입력이 모두 이 구현 위에서 완결되므로, S3 전환은 일정상
 * 선행 작업이 아니라 교체 작업이 된다.
 *
 * <p>이 어댑터가 쓴 행은 bucket_name이 {@code local-disk}로 남는다. S3 어댑터가
 * 추가돼도 그 행들은 계속 이 구현이 읽는다.
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

    private final Path rootDirectory;

    public LocalFileObjectStorageClient(StorageProperties properties) {
        this.rootDirectory = Path.of(properties.getLocalRootDirectory())
                .toAbsolutePath()
                .normalize();
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
