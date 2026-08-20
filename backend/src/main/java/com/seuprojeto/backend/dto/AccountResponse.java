package com.seuprojeto.backend.dto;

/** Response of {@code GET /api/auth/me}: who the current token belongs to. */
public record AccountResponse(Long id, String email) {
}
