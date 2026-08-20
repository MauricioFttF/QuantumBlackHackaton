package com.seuprojeto.backend.config;

import com.seuprojeto.backend.service.AuthService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/**
 * Schedules the expired-session purge. {@code @EnableScheduling} is already on
 * {@code ChatMemoryConfig} and only needs to appear once, so this class just contributes a task.
 *
 * <p>Like the conversation purge, this only reclaims space — {@code findValid} filters on
 * {@code expires_at}, so a session is dead on time regardless of when this runs.
 */
@Configuration
public class AuthConfig implements SchedulingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(AuthConfig.class);

    private final AuthProperties properties;
    private final AuthService authService;

    public AuthConfig(AuthProperties properties, AuthService authService) {
        this.properties = properties;
        this.authService = authService;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        log.info("Sessions last {}; purging expired ones every {}",
                properties.sessionTtl(), properties.sessionPurgePeriod());
        registrar.addFixedRateTask(authService::purgeExpiredSessions, properties.sessionPurgePeriod());
    }
}
