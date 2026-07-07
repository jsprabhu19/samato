package com.samato.authservice.web;

import com.samato.authservice.domain.Role;
import com.samato.authservice.domain.UserAccount;
import com.samato.authservice.domain.UserRepository;
import com.samato.shared.errors.DomainException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.Set;
import java.util.UUID;

/**
 * Registration endpoint.
 *
 * Why is this NOT the standard /signup in the OAuth2 spec?
 *   - The OAuth2 spec describes client registration, not user registration.
 *   - User registration is application-specific and lives outside the AS
 *     in most architectures. We keep it here for simplicity — in a real
 *     system, this would be a separate user-onboarding service.
 *
 * Note: anyone can register as a CUSTOMER. The other roles are
 * assigned by an admin (Phase 2 out of scope — we seed them via Flyway
 * for the bible to test role-based flows).
 */
@RestController
@RequestMapping("/api/auth")
public class RegistrationController {

    private final UserRepository users;
    private final PasswordEncoder encoder;

    public RegistrationController(UserRepository users, PasswordEncoder encoder) {
        this.users = users;
        this.encoder = encoder;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public RegisterResponse register(@Valid @RequestBody RegisterRequest req) {
        if (users.existsByEmail(req.email())) {
            throw new DomainException("EMAIL_TAKEN", "Email already registered", 409);
        }

        UserAccount u = new UserAccount();
        u.setEmail(req.email());
        u.setPasswordHash(encoder.encode(req.password()));
        u.setRoles(Set.of(Role.CUSTOMER));     // public registration → customer only
        users.save(u);

        return new RegisterResponse(u.getId().toString(), u.getEmail());
    }

    public record RegisterRequest(
            @Email @NotBlank String email,
            @NotBlank @Size(min = 8, max = 100) String password) {}

    public record RegisterResponse(String id, String email) {}
}
