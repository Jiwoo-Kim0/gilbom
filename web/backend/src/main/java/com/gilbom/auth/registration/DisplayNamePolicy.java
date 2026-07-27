package com.gilbom.auth.registration;

/**
 * 공개 회원가입과 프로필 변경이 공유할 표시 이름 입력 계약이다.
 */
public final class DisplayNamePolicy {

    public static final int MAX_LENGTH = 100;

    private DisplayNamePolicy() {
    }

    public static boolean isValid(String value) {
        if (value == null
                || value.isEmpty()
                || !value.equals(value.strip())
                || value.codePointCount(0, value.length()) > MAX_LENGTH) {
            return false;
        }

        return value.codePoints().noneMatch(Character::isISOControl);
    }
}
