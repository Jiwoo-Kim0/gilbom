package com.gilbom.auth.session;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class AuthenticationSessionConfiguration {

    @Bean
    public Clock authenticationSessionClock() {
        return Clock.systemUTC();
    }
}
