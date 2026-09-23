package devPilot.backend.security;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

import org.jetbrains.annotations.NotNull;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.core.user.OAuth2User;

import devPilot.backend.model.User;

public record AppUserPrincipal(User user, Map<String, Object> attributes) implements OAuth2User {

    public UUID getId() {
        return user.getUserId();
    }

    public User getUser() { return user; }

    @NotNull
    @Override
    public Map<String, Object> getAttributes() {
        return attributes;
    }

    @NotNull
    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return AuthorityUtils.createAuthorityList("ROLE_USER");
    }

    @NotNull
    @Override
    public String getName() {
        return user.getUserId().toString();
    }
}