package com.gilbom.auth.registration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class PublicRegistrationIdGeneratorTest {

    @Test
    void springContextUsesTheSecureRandomConstructor() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(
                             PublicRegistrationIdGenerator.class
                     )) {
            assertThat(context.getBean(PublicRegistrationIdGenerator.class))
                    .isNotNull();
        }
    }

    @Test
    void generatesNonSequentialCodesWithinTheDatabaseLimit() {
        PublicRegistrationIdGenerator generator =
                new PublicRegistrationIdGenerator();

        String first = generator.nextUserCode();
        String second = generator.nextUserCode();

        assertThat(first)
                .startsWith("USR-")
                .hasSizeLessThanOrEqualTo(30);
        assertThat(second).isNotEqualTo(first);
    }
}
