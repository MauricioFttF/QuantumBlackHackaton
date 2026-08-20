package com.seuprojeto.backend.controller;

import com.seuprojeto.backend.dto.AccountResponse;
import com.seuprojeto.backend.dto.AuthResponse;
import com.seuprojeto.backend.dto.LoginRequest;
import com.seuprojeto.backend.dto.RegisterRequest;
import com.seuprojeto.backend.model.AuthenticatedUser;
import com.seuprojeto.backend.service.AuthService;
import com.seuprojeto.backend.web.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Accounts and sessions. There is no email confirmation: registering signs you in immediately.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final String BEARER_PREFIX = "Bearer ";

    private final AuthService authService;
    private final CurrentUser currentUser;

    public AuthController(AuthService authService, CurrentUser currentUser) {
        this.authService = authService;
        this.currentUser = currentUser;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthResponse register(@RequestBody(required = false) RegisterRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Informe o e-mail e a senha");
        }
        return authService.register(request);
    }

    @PostMapping("/login")
    public AuthResponse login(@RequestBody(required = false) LoginRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Informe o e-mail e a senha");
        }
        return authService.login(request);
    }

    /**
     * Revokes the token used to make this call. Always 204, even for a token that was already
     * invalid — logout is idempotent and a client throwing its token away should not be arguing
     * with the server about it.
     */
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(HttpServletRequest httpRequest) {
        String header = httpRequest.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            authService.logout(header.substring(BEARER_PREFIX.length()).trim());
        }
    }

    /** Who the current token belongs to. Used by the UI to restore a session on reload. */
    @GetMapping("/me")
    public AccountResponse me(HttpServletRequest httpRequest) {
        AuthenticatedUser user = currentUser.require(httpRequest);
        return new AccountResponse(user.id(), user.email());
    }
}
