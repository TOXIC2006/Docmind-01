package com.lcdw.springai.docmind01.Service.Security;

import com.lcdw.springai.docmind01.Entity.User;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

/**
 * Carries the account id alongside the email so services can scope data to its owner
 * without a second database query on every request.
 */
@Getter
public class AppUserPrincipal implements UserDetails {

    private final String id;

    /** Login identity. Spring Security authenticates by email, so this is the email, not the display name. */
    private final String email;

    /** The username chosen at registration, for display only. */
    private final String displayName;

    private final String password;
    private final String role;
    private final boolean emailVerified;

    public AppUserPrincipal(User user) {
        this.id = user.getId();
        this.email = user.getEmail();
        this.displayName = user.getUsername();
        this.password = user.getPassword();
        this.role = user.getRole();
        this.emailVerified = user.isEmailVerified();
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role));
    }

    @Override
    public boolean isEnabled() {
        return emailVerified;
    }
}
