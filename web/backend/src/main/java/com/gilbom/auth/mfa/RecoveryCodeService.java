package com.gilbom.auth.mfa;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.gilbom.auth.password.PasswordHashingService;

@Service
public class RecoveryCodeService {

    public static final int CODE_COUNT = 10;
    public static final String CODE_PATTERN =
            "^(0[1-9]|10)-[A-Z2-7]{4}(?:-[A-Z2-7]{4}){3}$";

    private static final int RANDOM_BYTES = 10;
    private static final Pattern COMPILED_PATTERN =
            Pattern.compile(CODE_PATTERN);

    private final PasswordHashingService passwordHashingService;
    private final SecureRandom secureRandom;

    @Autowired
    public RecoveryCodeService(
            PasswordHashingService passwordHashingService
    ) {
        this(passwordHashingService, new SecureRandom());
    }

    RecoveryCodeService(
            PasswordHashingService passwordHashingService,
            SecureRandom secureRandom
    ) {
        this.passwordHashingService = Objects.requireNonNull(
                passwordHashingService,
                "passwordHashingService must not be null"
        );
        this.secureRandom = Objects.requireNonNull(
                secureRandom,
                "secureRandom must not be null"
        );
    }

    public List<GeneratedRecoveryCode> generateCodes() {
        List<GeneratedRecoveryCode> codes =
                new ArrayList<>(CODE_COUNT);
        for (int position = 1; position <= CODE_COUNT; position++) {
            byte[] random = new byte[RANDOM_BYTES];
            secureRandom.nextBytes(random);
            try {
                String randomPart =
                        group(Base32Codec.encodeWithoutPadding(random));
                String rawCode = String.format(
                        java.util.Locale.ROOT,
                        "%02d-%s",
                        position,
                        randomPart
                );
                codes.add(new GeneratedRecoveryCode(
                        position,
                        rawCode,
                        passwordHashingService.hashNewPassword(rawCode)
                ));
            } finally {
                Arrays.fill(random, (byte) 0);
            }
        }
        return List.copyOf(codes);
    }

    public OptionalInt parsePosition(String recoveryCode) {
        if (recoveryCode == null) {
            return OptionalInt.empty();
        }
        Matcher matcher = COMPILED_PATTERN.matcher(recoveryCode);
        if (!matcher.matches()) {
            return OptionalInt.empty();
        }
        return OptionalInt.of(Integer.parseInt(matcher.group(1)));
    }

    public boolean verify(String recoveryCode, String storedHash) {
        return passwordHashingService.verifyAgainstNullableHash(
                recoveryCode,
                storedHash
        );
    }

    private static String group(String ungrouped) {
        if (ungrouped.length() != 16) {
            throw new IllegalArgumentException(
                    "Recovery code random part must be 16 Base32 characters"
            );
        }
        return ungrouped.substring(0, 4)
                + "-" + ungrouped.substring(4, 8)
                + "-" + ungrouped.substring(8, 12)
                + "-" + ungrouped.substring(12, 16);
    }

    public record GeneratedRecoveryCode(
            int position,
            String rawCode,
            String encodedHash
    ) {
        public GeneratedRecoveryCode {
            if (position < 1 || position > CODE_COUNT) {
                throw new IllegalArgumentException(
                        "Recovery code position is invalid"
                );
            }
            if (rawCode == null
                    || !COMPILED_PATTERN.matcher(rawCode).matches()) {
                throw new IllegalArgumentException(
                        "Recovery code format is invalid"
                );
            }
            if (encodedHash == null
                    || !encodedHash.startsWith(
                            "{argon2}$argon2id$"
                    )) {
                throw new IllegalArgumentException(
                        "Recovery code hash must use Argon2id"
                );
            }
        }
    }
}
