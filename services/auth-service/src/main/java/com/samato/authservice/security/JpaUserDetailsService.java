package com.samato.authservice.security;

import com.samato.authservice.domain.UserAccount;
import com.samato.authservice.domain.UserRepository;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.stream.Collectors;

/**
 * Loads a user by email for Spring Security.
 *
 * Note: we expose roles as `ROLE_<NAME>` (Spring's convention). The
 * authorization server then puts them in the JWT's `roles` claim, and
 * downstream services use `@PreAuthorize("hasRole('CUSTOMER')")` etc.
 *
 * Interview tip: "ROLE_" prefix is Spring's convention; many teams
 * remove it in JWTs and re-add it via a JwtAuthenticationConverter to
 * keep the token clean.
 */
@Service
public class JpaUserDetailsService implements UserDetailsService {

    private final UserRepository repo;

    public JpaUserDetailsService(UserRepository repo) {
        this.repo = repo;
    }

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        UserAccount u = repo.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("No such user: " + email));
        return User.withUsername(u.getEmail())
                .password(u.getPasswordHash())
                .authorities(u.getRoles().stream()
                        .map(r -> new SimpleGrantedAuthority("ROLE_" + r.name()))
                        .collect(Collectors.toSet()))
                .build();
    }
}
