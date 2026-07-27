package com.gilbom.auth.account;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 로그인 ID로 사용하는 이메일의 형식과 정규화 계약이다.
 *
 * DNS·MX 조회나 메일 사업자별 별칭 처리는 하지 않는다.
 */
public final class EmailAddressPolicy {

    public static final int MAX_LENGTH = 254;

    private static final int MAX_LOCAL_PART_LENGTH = 64;
    private static final String ATOM = "[A-Z0-9!#$%&'*+/=?^_`{|}~-]+";
    private static final String DOMAIN_LABEL =
            "[A-Z0-9](?:[A-Z0-9-]{0,61}[A-Z0-9])?";
    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "^" + ATOM + "(?:\\." + ATOM + ")*@"
                    + DOMAIN_LABEL + "(?:\\." + DOMAIN_LABEL + ")+$",
            Pattern.CASE_INSENSITIVE
    );

    private EmailAddressPolicy() {
    }

    public static boolean hasValidFormat(String rawEmail) {
        if (rawEmail == null
                || rawEmail.isEmpty()
                || !rawEmail.equals(rawEmail.strip())
                || rawEmail.codePointCount(0, rawEmail.length()) > MAX_LENGTH) {
            return false;
        }

        int separator = rawEmail.lastIndexOf('@');
        if (separator < 1 || separator > MAX_LOCAL_PART_LENGTH) {
            return false;
        }

        return EMAIL_PATTERN.matcher(rawEmail).matches();
    }

    public static String normalize(String rawEmail) {
        if (!hasValidFormat(rawEmail)) {
            throw new IllegalArgumentException("email format is invalid");
        }
        return rawEmail.toLowerCase(Locale.ROOT);
    }
}
