package com.gilbom.storage.auth;

/**
 * 이미지 API 전용 권한이다.
 *
 * <p>관리자 RBAC(auth.permission)와 이름 공간이 겹치지 않게 별도로 둔다. 이 권한은
 * 로그인한 사용자가 아니라 요청이 제시한 자격증명(로봇 API Key, 서명된 URL)에서
 * 나오므로 역할에 부여하지 않는다.
 */
public final class ImageApiAuthorities {

    /** 로봇이 증거 이미지를 올릴 수 있다. */
    public static final String ROBOT_IMAGE_UPLOAD = "ROBOT_IMAGE_UPLOAD";

    /** 서명이 유효한 URL로 이미지 바이트를 읽을 수 있다. */
    public static final String IMAGE_CONTENT_READ = "IMAGE_CONTENT_READ";

    private ImageApiAuthorities() {
    }
}
