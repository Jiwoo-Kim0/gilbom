package com.gilbom.auth.password;

import java.util.Map;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 신규·초기·변경·dummy 비밀번호가 공유하는 유일한 Argon2id 설정이다.
 */
@Configuration
public class PasswordHashingConfiguration {

    public static final String ENCODER_ID = "argon2";
    public static final int SALT_LENGTH_BYTES = 16;
    public static final int HASH_LENGTH_BYTES = 32;
    public static final int PARALLELISM = 1;
    public static final int MEMORY_KIB = 19 * 1024;
    public static final int ITERATIONS = 2;

    @Bean
    public PasswordEncoder passwordEncoder() {
        Argon2PasswordEncoder argon2id = new Argon2PasswordEncoder(
                SALT_LENGTH_BYTES,
                HASH_LENGTH_BYTES,
                PARALLELISM,
                MEMORY_KIB,
                ITERATIONS
        );

        // {argon2} 저장 prefix는 유지하되 허용 map에는 Argon2id 하나만 둔다.
        return new DelegatingPasswordEncoder(
                ENCODER_ID,
                Map.of(ENCODER_ID, argon2id)
        );
    }
}
