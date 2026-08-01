package com.gilbom.storage;

import java.util.List;

import org.springframework.stereotype.Component;

import com.gilbom.common.error.BusinessException;
import com.gilbom.common.error.ErrorCode;

/**
 * 쓰기용 기본 저장소와, 행에 기록된 bucket으로 읽기용 저장소를 고르는 곳이다.
 *
 * <p>지금 등록된 구현은 로컬 디스크 하나뿐이다(2026-08-01 결정). 저장소가 하나 더
 * 늘어난다면 바꿀 코드는 이 클래스가 아니라 빈 등록뿐이다. 새 업로드는
 * {@link #primary()}가 가리키는 곳으로 가고, 이미 올라간 {@code local-disk} 행은
 * {@link #forBucket(String)}이 계속 로컬 어댑터로 돌린다.
 */
@Component
public class ObjectStorageRegistry {

    private final List<ObjectStorageClient> clients;

    public ObjectStorageRegistry(List<ObjectStorageClient> clients) {
        if (clients.isEmpty()) {
            throw new IllegalStateException(
                    "At least one ObjectStorageClient must be registered");
        }
        this.clients = List.copyOf(clients);
    }

    /**
     * 새 객체를 쓸 저장소. 등록된 구현이 여럿이면 마지막에 추가된 것이 우선이며,
     * 이는 조건부로 등록되는 빈이 있을 때 자연스럽게 운영 저장소가 우선되게 하기 위함이다.
     */
    public ObjectStorageClient primary() {
        return clients.getLast();
    }

    public ObjectStorageClient forBucket(String bucketName) {
        return clients.stream()
                .filter(client -> client.supports(bucketName))
                .findFirst()
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.S3_UNAVAILABLE,
                        "이미지를 보관한 저장소를 사용할 수 없습니다."));
    }
}
