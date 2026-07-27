package com.gilbom.auth.password;

/**
 * 회원가입·초기 비밀번호·마이페이지 변경이 공유하는 비밀번호 입력 계약이다.
 */
public final class PasswordPolicy {

    public static final int MIN_LENGTH = 10;
    public static final int MAX_LENGTH = 24;

    private PasswordPolicy() {
    }

    public static boolean hasValidLength(String rawPassword) {
        if (rawPassword == null) {
            return false;
        }

        // trim이나 Unicode 정규화를 하지 않고 사용자가 제출한 code point 전체를 센다.
        int length = rawPassword.codePointCount(0, rawPassword.length());
        return length >= MIN_LENGTH && length <= MAX_LENGTH;
    }
}
