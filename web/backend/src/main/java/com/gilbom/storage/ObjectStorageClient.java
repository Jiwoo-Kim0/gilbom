package com.gilbom.storage;

/**
 * 이미지 바이트가 실제로 놓이는 저장소의 port다.
 *
 * <p>20장 공통 규칙은 파일을 private 저장소에 두고 DB에는 bucket·object_key만
 * 남기도록 정한다. 그 규칙만 지키면 저장소 종류는 업무 코드가 알 필요가 없으므로
 * 여기서 끊는다.
 *
 * <p>구현은 로컬 디스크 하나뿐이다(2026-08-01 결정). 그래도 포트를 유지하는 이유는
 * 두 가지다. 업로드 단위 테스트가 이 포트를 스텁하고, 읽기가 {@code bucketName}을
 * 받는 덕에 저장소가 하나 더 생겨도 이미 올라간 행을 옮길 필요가 없다.
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
