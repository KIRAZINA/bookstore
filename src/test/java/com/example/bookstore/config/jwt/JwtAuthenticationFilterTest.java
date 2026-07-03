package com.example.bookstore.config.jwt;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JwtAuthenticationFilterTest {

    @Mock
    private JwtUtil jwtUtil;
    @Mock
    private HttpServletRequest request;
    @Mock
    private HttpServletResponse response;
    @Mock
    private FilterChain filterChain;

    private JwtAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        filter = new JwtAuthenticationFilter(jwtUtil);
        SecurityContextHolder.clearContext();
    }

    @Nested
    @DisplayName("doFilterInternal")
    class DoFilter {
        @Test
        @DisplayName("Sets authentication for valid JWT")
        void validJwt() throws Exception {
            when(request.getHeader("Authorization")).thenReturn("Bearer valid-token");
            when(jwtUtil.validateToken("valid-token")).thenReturn(true);
            when(jwtUtil.getUsernameFromToken("valid-token")).thenReturn("testuser");
            when(jwtUtil.getRolesFromToken("valid-token")).thenReturn("ROLE_USER");

            filter.doFilterInternal(request, response, filterChain);

            assertNotNull(SecurityContextHolder.getContext().getAuthentication());
            assertEquals("testuser", SecurityContextHolder.getContext().getAuthentication().getName());
            verify(filterChain).doFilter(request, response);
        }

        @Test
        @DisplayName("Sets authentication with multiple roles")
        void multipleRoles() throws Exception {
            when(request.getHeader("Authorization")).thenReturn("Bearer multi-role-token");
            when(jwtUtil.validateToken("multi-role-token")).thenReturn(true);
            when(jwtUtil.getUsernameFromToken("multi-role-token")).thenReturn("admin");
            when(jwtUtil.getRolesFromToken("multi-role-token")).thenReturn("ROLE_ADMIN,ROLE_USER");

            filter.doFilterInternal(request, response, filterChain);

            var auth = SecurityContextHolder.getContext().getAuthentication();
            assertEquals(2, auth.getAuthorities().size());
            assertTrue(auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN")));
            assertTrue(auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_USER")));
        }

        @Test
        @DisplayName("Does not set authentication when no Authorization header")
        void noAuthHeader() throws Exception {
            when(request.getHeader("Authorization")).thenReturn(null);

            filter.doFilterInternal(request, response, filterChain);

            assertNull(SecurityContextHolder.getContext().getAuthentication());
            verify(filterChain).doFilter(request, response);
        }

        @Test
        @DisplayName("Does not set authentication for non-Bearer header")
        void nonBearerHeader() throws Exception {
            when(request.getHeader("Authorization")).thenReturn("Basic dXNlcjpwYXNz");

            filter.doFilterInternal(request, response, filterChain);

            assertNull(SecurityContextHolder.getContext().getAuthentication());
            verify(filterChain).doFilter(request, response);
        }

        @Test
        @DisplayName("Does not set authentication for invalid JWT")
        void invalidJwt() throws Exception {
            when(request.getHeader("Authorization")).thenReturn("Bearer invalid-token");
            when(jwtUtil.validateToken("invalid-token")).thenReturn(false);

            filter.doFilterInternal(request, response, filterChain);

            assertNull(SecurityContextHolder.getContext().getAuthentication());
            verify(filterChain).doFilter(request, response);
        }

        @Test
        @DisplayName("Does not set authentication when roles token is empty")
        void emptyRoles() throws Exception {
            when(request.getHeader("Authorization")).thenReturn("Bearer token-no-roles");
            when(jwtUtil.validateToken("token-no-roles")).thenReturn(true);
            when(jwtUtil.getUsernameFromToken("token-no-roles")).thenReturn("user");
            when(jwtUtil.getRolesFromToken("token-no-roles")).thenReturn("");

            filter.doFilterInternal(request, response, filterChain);

            var auth = SecurityContextHolder.getContext().getAuthentication();
            assertNotNull(auth);
            assertTrue(auth.getAuthorities().isEmpty());
        }

        @Test
        @DisplayName("Continues filter chain even on exception")
        void exceptionInFilter() throws Exception {
            when(request.getHeader("Authorization")).thenReturn("Bearer bad-token");
            when(jwtUtil.validateToken("bad-token")).thenThrow(new RuntimeException("JWT parser error"));

            filter.doFilterInternal(request, response, filterChain);

            assertNull(SecurityContextHolder.getContext().getAuthentication());
            verify(filterChain).doFilter(request, response);
        }
    }
}
