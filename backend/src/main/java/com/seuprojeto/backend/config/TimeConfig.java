package com.seuprojeto.backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * The clock the application reads time from.
 *
 * <p>Injected rather than called statically so that time-dependent behaviour — what still falls
 * inside {@code app.chat-memory.ttl}, what has expired — is testable without sleeping.
 *
 * <p>Deliberately its own configuration class: {@code ChatMemoryConfig} needs
 * {@code ConversationMemory} injected to schedule the purge, and {@code ConversationMemory} needs
 * this clock. Declaring the bean there would make the two beans depend on each other's
 * construction and fail startup with {@code BeanCurrentlyInCreationException}.
 */
@Configuration
public class TimeConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
