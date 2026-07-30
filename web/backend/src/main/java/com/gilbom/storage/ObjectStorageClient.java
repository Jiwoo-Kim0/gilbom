package com.gilbom.storage;

/**
 * 이미지 바이트가 실제로 놓이는 저장소의 port다.
 *
 * <p>20장 공통 규칙은 파일을 private 저장소에 두고 DB에는 bucket·object_key만
 * 남기도록 정한다. 그 규칙만 지키면 로컬 디스크와 S3는 교체 가능한 구현이므로,
 * 업로드·조회 업무 코드가 저장소 종류를 알지 못하도록 여기서 끊는다.
 *
 * <p>읽기가 {@code bucketName}을 받는 이유는 전환 때문이다. S3로 넘어가도 그 전에
 * 올라간 행은 {@code local-disk} bucket을 가리키므로, 구현을 갈아끼우는 것이 아니라
 * 두 구현을 동시에 두고 행의 bucket으로 고른다. 그래야 재업로드나 데이터
 * 마이그레이션 없이 전환할 수 있다.
 */
public interface ObjectStorageClient {

    /**
     * 새로 저장하는 객체가 들어갈 bucket 이름. {@code storage.robot_image.bucket_name}에
     * 그대로 기록된다.
     */
    String bucketName();

    /**
     * @return 이 구현이 해당 bucket의 객체를 읽을 수 있는지
     */
    boolean supports(String bucketName);

    void put(String objectKey, byte[] content, String contentType);

    /**
     * @throws com.gilbom.common.error.BusinessException 객체가 없거나 저장소를 쓸 수 없을 때
     */
    byte[] read(String bucketName, String objectKey);
}
