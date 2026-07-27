package com.gilbom.auth.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

class OperationalCookieConfigurationTest {

    private final YamlPropertySourceLoader loader =
            new YamlPropertySourceLoader();

    @Test
    void productionProfileEnforcesTheHostCookieContract()
            throws IOException {
        List<PropertySource<?>> common = loader.load(
                "common",
                new ClassPathResource("application.yml")
        );
        List<PropertySource<?>> production = loader.load(
                "ec2",
                new ClassPathResource("application-ec2.yml")
        );

        assertThat(property(
                production,
                "server.servlet.session.cookie.name"
        )).isEqualTo("__Host-GILBOM_SESSION");
        assertThat(property(
                production,
                "server.servlet.session.cookie.secure"
        )).isEqualTo(true);
        assertThat(property(
                common,
                "server.servlet.session.cookie.http-only"
        )).isEqualTo(true);
        assertThat(property(
                common,
                "server.servlet.session.cookie.same-site"
        )).isEqualTo("lax");
        assertThat(property(
                common,
                "server.servlet.session.cookie.path"
        )).isEqualTo("/");
        assertThat(property(
                common,
                "server.servlet.session.cookie.domain"
        )).isNull();
    }

    private static Object property(
            List<PropertySource<?>> sources,
            String name
    ) {
        return sources.stream()
                .map(source -> source.getProperty(name))
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(null);
    }
}
