package com.seuprojeto.backend.error;

/**
 * Registration hit an address that already has an account.
 *
 * <p>This does tell the caller that an address is registered, which login refuses to do. Without
 * email confirmation there is no way around it: the alternative is accepting the registration and
 * doing nothing, which would leave a real user unable to explain why their new account does not
 * work. Recorded as a known trade-off in CLAUDE.md §4.
 */
public class EmailAlreadyRegisteredException extends RuntimeException {

    public EmailAlreadyRegisteredException(String message) {
        super(message);
    }

    public EmailAlreadyRegisteredException(String message, Throwable cause) {
        super(message, cause);
    }
}
