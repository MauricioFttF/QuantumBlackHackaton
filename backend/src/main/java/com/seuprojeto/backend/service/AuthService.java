package com.seuprojeto.backend.service;

import com.seuprojeto.backend.config.AuthProperties;
import com.seuprojeto.backend.dto.AuthResponse;
import com.seuprojeto.backend.dto.LoginRequest;
import com.seuprojeto.backend.dto.RegisterRequest;
import com.seuprojeto.backend.error.EmailAlreadyRegisteredException;
import com.seuprojeto.backend.error.InvalidCredentialsException;
import com.seuprojeto.backend.model.AppUser;
import com.seuprojeto.backend.model.AuthenticatedUser;
import com.seuprojeto.backend.model.EmailAddress;
import com.seuprojeto.backend.model.UserSession;
import com.seuprojeto.backend.repository.AppUserRepository;
import com.seuprojeto.backend.repository.UserSessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Registration, login, logout and bearer-token authentication.
 *
 * <p>Four decisions worth knowing before changing anything here:
 *
 * <ul>
 *   <li><b>Passwords are hashed by BCrypt, never by us.</b> Cost comes from
 *       {@code app.auth.bcrypt-strength}; the hash carries its own cost factor, so raising it
 *       affects new hashes and leaves old ones verifiable.
 *   <li><b>The session token is 256 bits of {@link SecureRandom} and is stored only as a
 *       SHA-256.</b> A plain hash is right here: there is no low-entropy secret to brute-force,
 *       unlike a password. Stealing the table gets you hashes you cannot present.
 *   <li><b>Login never says which half was wrong.</b> Unknown address and wrong password both
 *       raise {@link InvalidCredentialsException}, and the unknown-address path still runs one
 *       BCrypt comparison against a throwaway hash so that "no such user" does not answer
 *       measurably faster than "wrong password".
 *   <li><b>Expiry is enforced in the query</b> ({@link UserSessionRepository#findValid}), so a
 *       session dies on time whether or not {@link #purgeExpiredSessions()} has run.
 * </ul>
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    /** 256 bits. Enough that guessing a live token is not a threat model. */
    private static final int TOKEN_BYTES = 32;

    private final AppUserRepository users;
    private final UserSessionRepository sessions;
    private final PasswordEncoder passwordEncoder;
    private final AuthProperties properties;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    /**
     * A hash of a password nobody knows, compared against when the address is unknown. Generated
     * once at startup rather than hard-coded so it costs the same as any real hash on this
     * machine's configured strength.
     */
    private final String throwawayHash;

    public AuthService(AppUserRepository users,
                       UserSessionRepository sessions,
                       PasswordEncoder passwordEncoder,
                       AuthProperties properties,
                       Clock clock) {
        this.users = users;
        this.sessions = sessions;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
        this.clock = clock;
        this.throwawayHash = passwordEncoder.encode("unused-" + java.util.UUID.randomUUID());
    }

    /**
     * Creates an account and signs it in. There is no email confirmation, so the address is
     * claimed rather than verified.
     *
     * @throws IllegalArgumentException          malformed address or a password the policy rejects
     * @throws EmailAlreadyRegisteredException   the address already has an account
     */
    @Transactional
    public AuthResponse register(RegisterRequest request) {
        EmailAddress email = new EmailAddress(request.email());
        PasswordPolicy.validate(request.password());

        if (users.findByEmail(email.value()).isPresent()) {
            throw new EmailAlreadyRegisteredException("Este e-mail já está cadastrado");
        }

        AppUser user;
        try {
            user = users.save(new AppUser(email, passwordEncoder.encode(request.password()),
                    clock.instant()));
        } catch (DataIntegrityViolationException e) {
            // findByEmail is a read, so two concurrent registrations of the same address both pass
            // the check above and race here. The unique constraint keeps the data correct; this
            // turns the crash into the same 409 the sequential case produces.
            throw new EmailAlreadyRegisteredException("Este e-mail já está cadastrado", e);
        }

        log.info("Registered account {}", user.getId());
        return startSession(user);
    }

    /**
     * @throws InvalidCredentialsException whenever the pair does not match, whatever the reason
     */
    @Transactional
    public AuthResponse login(LoginRequest request) {
        Optional<AppUser> found = normalise(request.email()).flatMap(users::findByEmail);

        if (found.isEmpty()) {
            // Spend the comparison anyway: skipping it would make an unknown address answer in a
            // fraction of the time and turn this endpoint into an account-existence oracle.
            passwordEncoder.matches(request.password(), throwawayHash);
            throw new InvalidCredentialsException("E-mail ou senha inválidos");
        }

        AppUser user = found.get();
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new InvalidCredentialsException("E-mail ou senha inválidos");
        }

        log.info("Account {} signed in", user.getId());
        return startSession(user);
    }

    /**
     * Revokes one session — this device, not every session of the account. Unknown or already
     * expired tokens are accepted silently: logout is idempotent, and a client that has thrown its
     * token away should not be told it was invalid.
     */
    @Transactional
    public void logout(String token) {
        if (token == null || token.isBlank()) {
            return;
        }
        sessions.deleteByTokenHash(sha256Hex(token));
    }

    /**
     * @return who the token belongs to, or empty when it is unknown, expired or malformed. No
     *         exception: an absent or bad token is an ordinary unauthenticated request, and the
     *         caller decides whether that is allowed
     */
    @Transactional(readOnly = true)
    public Optional<AuthenticatedUser> authenticate(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        return sessions.findValid(sha256Hex(token), clock.instant())
                .flatMap(session -> users.findById(session.getUserId()))
                .map(user -> new AuthenticatedUser(user.getId(), user.getEmail()));
    }

    /** Deletes sessions past their expiry. Scheduled by {@code AuthConfig}. */
    @Transactional
    public int purgeExpiredSessions() {
        int deleted = sessions.deleteExpired(clock.instant());
        if (deleted > 0) {
            log.info("Purged {} expired session(s)", deleted);
        }
        return deleted;
    }

    private AuthResponse startSession(AppUser user) {
        byte[] raw = new byte[TOKEN_BYTES];
        random.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);

        Instant now = clock.instant();
        Instant expiresAt = now.plus(properties.sessionTtl());
        sessions.save(new UserSession(sha256Hex(token), user.getId(), now, expiresAt));

        return new AuthResponse(token, expiresAt, user.getEmail());
    }

    /**
     * Reuses {@link EmailAddress} normalisation so a login finds the account a registration
     * created. A malformed address cannot match anything stored, so it is treated as a failed
     * lookup rather than a validation error — login answers the same way for every bad input.
     */
    private static Optional<String> normalise(String email) {
        try {
            return Optional.of(new EmailAddress(email).value());
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is required of every JVM; if it is missing, nothing here can work.
            throw new IllegalStateException("SHA-256 is unavailable on this JVM", e);
        }
    }
}
