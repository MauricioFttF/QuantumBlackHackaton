package com.seuprojeto.backend.controller;

import com.seuprojeto.backend.config.RateLimitProperties;
import com.seuprojeto.backend.config.WebProperties;
import com.seuprojeto.backend.dto.AuthResponse;
import com.seuprojeto.backend.error.EmailAlreadyRegisteredException;
import com.seuprojeto.backend.error.GlobalExceptionHandler;
import com.seuprojeto.backend.error.InvalidCredentialsException;
import com.seuprojeto.backend.model.AuthenticatedUser;
import com.seuprojeto.backend.service.AuthService;
import com.seuprojeto.backend.web.CurrentUser;
import com.seuprojeto.backend.web.RateLimiter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AuthController.class)
@Import({GlobalExceptionHandler.class, RateLimiter.class, CurrentUser.class})
@EnableConfigurationProperties({WebProperties.class, RateLimitProperties.class})
@TestPropertySource(properties = {
        "app.web.cors-allowed-origins=http://localhost:3000",
        "app.rate-limit.enabled=true",
        "app.rate-limit.requests-per-minute-per-client=100",
        "app.rate-limit.requests-per-day-total=1000",
        "app.rate-limit.auth-requests-per-minute-per-client=100",
        "app.rate-limit.recommend-requests-per-minute-per-client=100",
        "app.rate-limit.trust-forwarded-header=false",
})
class AuthControllerTest {

    private static final Instant EXPIRES_AT = Instant.parse("2026-08-20T12:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuthService authService;

    @Test
    void register_validAccount_returns201WithASession() throws Exception {
        when(authService.register(any()))
                .thenReturn(new AuthResponse("tok", EXPIRES_AT, "pedro@usp.br"));

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"pedro@usp.br\",\"password\":\"senha-bem-boa\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.token").value("tok"))
                .andExpect(jsonPath("$.email").value("pedro@usp.br"))
                .andExpect(jsonPath("$.expiresAt").exists());
    }

    @Test
    void register_addressAlreadyTaken_returns409() throws Exception {
        when(authService.register(any()))
                .thenThrow(new EmailAlreadyRegisteredException("Este e-mail já está cadastrado"));

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"pedro@usp.br\",\"password\":\"senha-bem-boa\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Este e-mail já está cadastrado"));
    }

    @Test
    void register_passwordThePolicyRejects_returns400WithTheRule() throws Exception {
        when(authService.register(any()))
                .thenThrow(new IllegalArgumentException("A senha deve ter pelo menos 8 caracteres"));

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"pedro@usp.br\",\"password\":\"curta\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("A senha deve ter pelo menos 8 caracteres"));
    }

    @Test
    void register_missingFields_returns400WithoutCallingTheService() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"  \",\"password\":\"senha-bem-boa\"}"))
                .andExpect(status().isBadRequest());

        verify(authService, never()).register(any());
    }

    @Test
    void login_correctCredentials_returns200WithASession() throws Exception {
        when(authService.login(any()))
                .thenReturn(new AuthResponse("tok", EXPIRES_AT, "pedro@usp.br"));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"pedro@usp.br\",\"password\":\"senha-bem-boa\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("tok"));
    }

    @Test
    void login_badCredentials_returns401WithAMessageThatRevealsNothing() throws Exception {
        when(authService.login(any()))
                .thenThrow(new InvalidCredentialsException("E-mail ou senha inválidos"));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"pedro@usp.br\",\"password\":\"errada\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.title").value("Credenciais inválidas"))
                .andExpect(jsonPath("$.detail").value("E-mail ou senha inválidos"));
    }

    @Test
    void logout_revokesTheTokenItWasCalledWith() throws Exception {
        mockMvc.perform(post("/api/auth/logout")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer tok"))
                .andExpect(status().isNoContent());

        verify(authService).logout("tok");
    }

    @Test
    void logout_withoutAToken_isStill204() throws Exception {
        mockMvc.perform(post("/api/auth/logout"))
                .andExpect(status().isNoContent());

        verify(authService, never()).logout(anyString());
    }

    @Test
    void me_validToken_returnsTheAccount() throws Exception {
        when(authService.authenticate("tok"))
                .thenReturn(Optional.of(new AuthenticatedUser(42L, "pedro@usp.br")));

        mockMvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer tok"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(42))
                .andExpect(jsonPath("$.email").value("pedro@usp.br"));
    }

    @Test
    void me_withoutAToken_returns401() throws Exception {
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized());
    }
}
