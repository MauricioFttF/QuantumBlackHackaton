package com.seuprojeto.backend.repository;

import com.seuprojeto.backend.model.AppUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    /**
     * Looks an account up by its already-normalised address — callers pass
     * {@code EmailAddress.value()}, never raw user input, or a differently-cased spelling of a
     * registered address would look like an unknown account.
     */
    Optional<AppUser> findByEmail(String email);
}
