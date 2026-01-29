package com.example.bookstore.service;

import com.example.bookstore.model.AppUser;
import com.example.bookstore.model.UserRole;
import com.example.bookstore.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    private UserService userService;

    @BeforeEach
    void setUp() {
        userService = new UserService(userRepository);
        ReflectionTestUtils.setField(userService, "passwordEncoder", passwordEncoder);
    }

    @Test
    void registerUser_ShouldSaveNewUser() {
        AppUser user = new AppUser();
        user.setUsername("newuser");
        user.setPassword("password123");
        user.setEmail("newuser@example.com");

        when(userRepository.findByUsername("newuser")).thenReturn(Optional.empty());
        when(passwordEncoder.encode("password123")).thenReturn("encodedPassword");
        when(userRepository.save(any(AppUser.class))).thenAnswer(invocation -> {
            AppUser saved = invocation.getArgument(0);
            saved.setId(1L);
            return saved;
        });

        AppUser result = userService.registerUser(user);

        assertNotNull(result);
        assertEquals("newuser", result.getUsername());
        assertEquals("encodedPassword", result.getPassword());
        assertEquals(UserRole.ROLE_USER, result.getRole());
        verify(userRepository).save(any(AppUser.class));
    }

    @Test
    void registerUser_ShouldThrowExceptionForDuplicateUsername() {
        AppUser user = new AppUser();
        user.setUsername("existinguser");
        user.setPassword("password123");

        when(userRepository.findByUsername("existinguser")).thenReturn(Optional.of(new AppUser()));

        assertThrows(IllegalArgumentException.class, () -> userService.registerUser(user));
        verify(userRepository, never()).save(any(AppUser.class));
    }

    @Test
    void findByUsername_ShouldReturnUserWhenExists() {
        AppUser existingUser = new AppUser();
        existingUser.setId(1L);
        existingUser.setUsername("testuser");

        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(existingUser));

        Optional<AppUser> result = userService.findByUsername("testuser");

        assertTrue(result.isPresent());
        assertEquals("testuser", result.get().getUsername());
    }

    @Test
    void findByUsername_ShouldReturnEmptyWhenNotExists() {
        when(userRepository.findByUsername("nonexistent")).thenReturn(Optional.empty());

        Optional<AppUser> result = userService.findByUsername("nonexistent");

        assertTrue(result.isEmpty());
    }

    @Test
    void loadUserByUsername_ShouldReturnUserDetails() {
        AppUser appUser = new AppUser();
        appUser.setUsername("testuser");
        appUser.setPassword("encodedPassword");
        appUser.setRole(UserRole.ROLE_USER);

        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(appUser));

        UserDetails userDetails = userService.loadUserByUsername("testuser");

        assertNotNull(userDetails);
        assertEquals("testuser", userDetails.getUsername());
        assertTrue(userDetails.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_USER")));
    }

    @Test
    void loadUserByUsername_ShouldThrowExceptionWhenUserNotFound() {
        when(userRepository.findByUsername("nonexistent")).thenReturn(Optional.empty());

        assertThrows(UsernameNotFoundException.class, 
                () -> userService.loadUserByUsername("nonexistent"));
    }

    @Test
    void loadUserByUsername_ShouldReturnAdminRole() {
        AppUser appUser = new AppUser();
        appUser.setUsername("admin");
        appUser.setPassword("encodedPassword");
        appUser.setRole(UserRole.ROLE_ADMIN);

        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(appUser));

        UserDetails userDetails = userService.loadUserByUsername("admin");

        assertTrue(userDetails.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN")));
    }
}
