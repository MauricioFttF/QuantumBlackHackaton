package com.seuprojeto.backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * The password hasher, from {@code spring-security-crypto} — the one piece of authentication this
 * project does not hand-roll, because writing a password hash is exactly the wrong place to be
 * original.
 *
 * <p>Its own configuration class for the reason recorded in §3.1.19: {@code AuthConfig} takes
 * {@code AuthService} in its constructor to schedule the session purge, and {@code AuthService}
 * needs this bean. Declaring it there would make the two beans wait on each other and fail startup
 * with {@code BeanCurrentlyInCreationException}.
 */
@Configuration
public class PasswordEncoderConfig {

    @Bean
    public PasswordEncoder passwordEncoder(AuthProperties properties) {
        return new BCryptPasswordEncoder(properties.bcryptStrength());
    }
}
