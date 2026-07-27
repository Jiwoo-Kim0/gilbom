package com.gilbom.auth.bootstrap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class InitialAdminBootstrapRunner implements ApplicationRunner {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(InitialAdminBootstrapRunner.class);

    private final InitialAdminBootstrapProperties properties;
    private final InitialAdminBootstrapService bootstrapService;

    public InitialAdminBootstrapRunner(
            InitialAdminBootstrapProperties properties,
            InitialAdminBootstrapService bootstrapService
    ) {
        this.properties = properties;
        this.bootstrapService = bootstrapService;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        if (!properties.isEnabled()) {
            return;
        }

        InitialAdminBootstrapResult result =
                bootstrapService.bootstrap(properties);
        // 이메일·비밀번호·hash·사용자 UUID는 기록하지 않는다.
        LOGGER.info("Initial administrator bootstrap result={}", result);
    }
}
