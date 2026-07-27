package com.gilbom.auth.password;

import java.nio.CharBuffer;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * 모든 비밀번호 hash 생성과 실제 또는 dummy hash 검증의 단일 진입점이다.
 */
@Service
public class PasswordHashingService {

    private static final int DUMMY_PASSWORD_LENGTH = 24;
    private static final char[] DUMMY_PASSWORD_ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
                    .toCharArray();

    private final PasswordEncoder passwordEncoder;
    private final String dummyPasswordHash;

    @Autowired
    public PasswordHashingService(PasswordEncoder passwordEncoder) {
        this(passwordEncoder, new SecureRandom());
    }

    PasswordHashingService(
            PasswordEncoder passwordEncoder,
            SecureRandom secureRandom
    ) {
        this.passwordEncoder =
                Objects.requireNonNull(passwordEncoder, "passwordEncoder must not be null");
        Objects.requireNonNull(secureRandom, "secureRandom must not be null");
        this.dummyPasswordHash = createDummyHash(passwordEncoder, secureRandom);
    }

    public String hashNewPassword(String rawPassword) {
        if (!PasswordPolicy.hasValidLength(rawPassword)) {
            throw new IllegalArgumentException("password length is invalid");
        }

        // trim·대소문자 변경·정규화·잘라내기 없이 원문 전체를 encoder에 전달한다.
        return passwordEncoder.encode(rawPassword);
    }

    /**
     * 저장 hash가 없더라도 프로세스 시작 시 한 번 만든 dummy hash를 같은 encoder로 검증한다.
     */
    public boolean verifyAgainstNullableHash(
            String rawPassword,
            String storedPasswordHash
    ) {
        Objects.requireNonNull(rawPassword, "rawPassword must not be null");

        String verificationHash =
                storedPasswordHash == null ? dummyPasswordHash : storedPasswordHash;
        boolean matches = passwordEncoder.matches(rawPassword, verificationHash);
        return storedPasswordHash != null && matches;
    }

    String dummyPasswordHashForTesting() {
        return dummyPasswordHash;
    }

    private static String createDummyHash(
            PasswordEncoder passwordEncoder,
            SecureRandom secureRandom
    ) {
        char[] randomPassword = new char[DUMMY_PASSWORD_LENGTH];
        for (int index = 0; index < randomPassword.length; index++) {
            randomPassword[index] = DUMMY_PASSWORD_ALPHABET[
                    secureRandom.nextInt(DUMMY_PASSWORD_ALPHABET.length)
            ];
        }
        try {
            return passwordEncoder.encode(CharBuffer.wrap(randomPassword));
        } finally {
            Arrays.fill(randomPassword, '\0');
        }
    }
}
