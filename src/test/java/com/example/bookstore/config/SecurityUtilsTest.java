package com.example.bookstore.config;

import com.example.bookstore.model.AppUser;
import com.example.bookstore.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SecurityUtilsTest {

    @Mock
    private UserRepository userRepository;

    private SecurityUtils securityUtils;

    @BeforeEach
    void setUp() {
        securityUtils = new SecurityUtils(userRepository);
        SecurityContextHolder.clearContext();
    }

    @Nested
    @DisplayName("getCurrentUser")
    class GetCurrentUser {

        @Test
        @DisplayName("Returns user from SecurityContext with UserDetails principal")
        void fromUserDetails() {
            AppUser appUser = new AppUser();
            appUser.setId(1L);
            appUser.setUsername("testuser");
            when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(appUser));

            Authentication auth = new UsernamePasswordAuthenticationToken(
                    new User("testuser", "pass", List.of(new SimpleGrantedAuthority("ROLE_USER"))),
                    null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
            SecurityContextHolder.getContext().setAuthentication(auth);

            AppUser result = securityUtils.getCurrentUser();
            assertEquals("testuser", result.getUsername());
        }

        @Test
        @DisplayName("Returns user with String principal")
        void fromStringPrincipal() {
            AppUser appUser = new AppUser();
            appUser.setId(1L);
            appUser.setUsername("admin");
            when(userRepository.findByUsername("admin")).thenReturn(Optional.of(appUser));

            Authentication auth = new UsernamePasswordAuthenticationToken("admin", null,
                    List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN")));
            SecurityContextHolder.getContext().setAuthentication(auth);

            AppUser result = securityUtils.getCurrentUser();
            assertEquals("admin", result.getUsername());
        }

        @Test
        @DisplayName("Throws when not authenticated")
        void noAuth() {
            SecurityContextHolder.getContext().setAuthentication(null);
            assertThrows(IllegalStateException.class, () -> securityUtils.getCurrentUser());
        }

        @Test
        @DisplayName("Throws when user not found in repository")
        void userNotFound() {
            when(userRepository.findByUsername("unknown")).thenReturn(Optional.empty());

            Authentication auth = new UsernamePasswordAuthenticationToken(
                    new User("unknown", "pass", List.of(new SimpleGrantedAuthority("ROLE_USER"))),
                    null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
            SecurityContextHolder.getContext().setAuthentication(auth);

            assertThrows(IllegalStateException.class, () -> securityUtils.getCurrentUser());
        }

        @Test
        @DisplayName("Throws with unknown principal type")
        void unknownPrincipal() {
            Authentication auth = new UsernamePasswordAuthenticationToken(123, null);
            SecurityContextHolder.getContext().setAuthentication(auth);

            assertThrows(IllegalStateException.class, () -> securityUtils.getCurrentUser());
        }
    }
}
