package com.yatrarail.security;

import com.yatrarail.entity.User;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

@Getter
public class UserPrincipal implements UserDetails {

    private final Long id;
    private final String email;
    private final String password;
    private final String fullName;
    private final String role;
    private final boolean active;
    /** STANDARD accounts own a real password; TEMPORARY accounts are guest one-time sessions. */
    private final String accountType;
    private final boolean temporary;
    private final LocalDateTime tempExpiresAt;
    private final LocalDateTime tempRevokedAt;

    public static final String ACCOUNT_STANDARD = "STANDARD";
    public static final String ACCOUNT_TEMPORARY = "TEMPORARY";

    public UserPrincipal(User user) {
        this.id = user.getId();
        this.email = user.getEmail();
        this.password = user.getPassword();
        this.fullName = user.getFullName();
        this.role = user.getRole().name();
        this.active = Boolean.TRUE.equals(user.getActive());
        // The flag is the source of truth, not the role, so a mislabelled row cannot quietly
        // acquire permanent-account powers.
        this.temporary = Boolean.TRUE.equals(user.getTemporaryAccount());
        this.accountType = temporary ? ACCOUNT_TEMPORARY : ACCOUNT_STANDARD;
        this.tempExpiresAt = user.getTempExpiresAt();
        this.tempRevokedAt = user.getTempRevokedAt();
    }

    /**
     * Whether this principal's session may still act. Standard accounts only need to be active.
     * A temporary session additionally must not be past its deadline, revoked, or deactivated -
     * checked on every request so "end session" and expiry take effect immediately rather than
     * whenever the JWT happens to expire.
     */
    public boolean isSessionLive() {
        if (!active) {
            return false;
        }
        if (!temporary) {
            return true;
        }
        return tempRevokedAt == null
                && tempExpiresAt != null
                && LocalDateTime.now().isBefore(tempExpiresAt);
    }

    /** Why the session is not live, for an honest 401/403 message. Null when it is live. */
    public String sessionEndReason() {
        if (!active) {
            return "This session is no longer active";
        }
        if (!temporary) {
            return null;
        }
        if (tempRevokedAt != null) {
            return "This temporary session has been ended";
        }
        if (tempExpiresAt == null || !LocalDateTime.now().isBefore(tempExpiresAt)) {
            return "This temporary session has expired";
        }
        return null;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role));
    }

    @Override
    public String getPassword() {
        return password;
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public boolean isAccountNonExpired() {
        // Lets Spring's own account-expiry handling agree with our explicit filter check.
        return !temporary || (tempRevokedAt == null
                && tempExpiresAt != null
                && LocalDateTime.now().isBefore(tempExpiresAt));
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return active;
    }
}
