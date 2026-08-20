package com.seuprojeto.backend.error;

/**
 * Wrong email or wrong password — deliberately not distinguished.
 *
 * <p>One exception for both cases because the response must not reveal whether an address has an
 * account. The message is fixed at the boundary for the same reason.
 */
public class InvalidCredentialsException extends RuntimeException {

    public InvalidCredentialsException(String message) {
        super(message);
    }
}
