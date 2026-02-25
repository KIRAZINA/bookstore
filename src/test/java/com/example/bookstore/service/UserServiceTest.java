package com.example.bookstore.service;

import com.example.bookstore.model.AppUser;
import com.example.bookstore.model.UserRole;
import com.example.bookstore.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private UserService userService;

    private AppUser testUser;

    @BeforeEach
    void setUp() {
        testUser = new AppUser();
        testUser.setId(1L);
        testUser.setUsername("testuser");
        testUser.setPassword("encodedPassword");
        testUser.setEmail("test@example.com");
        testUser.setRole(UserRole.ROLE_USER);
    }

    @Nested
    @DisplayName("registerUser")
    class RegisterUserTests {

        @Test
        @DisplayName("Успішна реєстрація нового користувача")
        void registerUser_Success() {
            AppUser newUser = new AppUser();
            newUser.setUsername("newuser");
            newUser.setPassword("rawPassword");
            newUser.setEmail("new@example.com");

            when(userRepository.findByUsername("newuser")).thenReturn(Optional.empty());
            when(passwordEncoder.encode("rawPassword")).thenReturn("encodedPassword");
            when(userRepository.save(any(AppUser.class))).thenAnswer(invocation -> {
                AppUser user = invocation.getArgument(0);
                user.setId(1L);
                return user;
            });

            AppUser result = userService.registerUser(newUser);

            assertNotNull(result);
            assertEquals("newuser", result.getUsername());
            assertEquals(UserRole.ROLE_USER, result.getRole());
            verify(passwordEncoder).encode("rawPassword");
            verify(userRepository).save(any(AppUser.class));
        }

        @Test
        @DisplayName("Реєстрація - користувач вже існує")
        void registerUser_UserAlreadyExists() {
            AppUser newUser = new AppUser();
            newUser.setUsername("existinguser");
            newUser.setPassword("password");

            when(userRepository.findByUsername("existinguser")).thenReturn(Optional.of(testUser));

            assertThrows(IllegalArgumentException.class, () -> userService.registerUser(newUser));
            verify(userRepository, never()).save(any());
        }

        @Test
        @DisplayName("Реєстрація - встановлюється роль USER за замовчуванням")
        void registerUser_DefaultRole() {
            AppUser newUser = new AppUser();
            newUser.setUsername("newuser");
            newUser.setPassword("password");

            when(userRepository.findByUsername("newuser")).thenReturn(Optional.empty());
            when(passwordEncoder.encode(anyString())).thenReturn("encoded");
            when(userRepository.save(any(AppUser.class))).thenAnswer(invocation -> invocation.getArgument(0));

            AppUser result = userService.registerUser(newUser);

            assertEquals(UserRole.ROLE_USER, result.getRole());
        }
    }

    @Nested
    @DisplayName("findByUsername")
    class FindByUsernameTests {

        @Test
        @DisplayName("Знаходження існуючого користувача")
        void findByUsername_Found() {
            when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));

            Optional<AppUser> result = userService.findByUsername("testuser");

            assertTrue(result.isPresent());
            assertEquals("testuser", result.get().getUsername());
        }

        @Test
        @DisplayName("Знаходження неіснуючого користувача")
        void findByUsername_NotFound() {
            when(userRepository.findByUsername("nonexistent")).thenReturn(Optional.empty());

            Optional<AppUser> result = userService.findByUsername("nonexistent");

            assertFalse(result.isPresent());
        }
    }

    @Nested
    @DisplayName("loadUserByUsername")
    class LoadUserByUsernameTests {

        @Test
        @DisplayName("Завантаження існуючого користувача")
        void loadUserByUsername_Found() {
            when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));

            UserDetails result = userService.loadUserByUsername("testuser");

            assertNotNull(result);
            assertEquals("testuser", result.getUsername());
            assertTrue(result.getAuthorities().stream()
                    .anyMatch(a -> a.getAuthority().equals("ROLE_USER")));
        }

        @Test
        @DisplayName("Завантаження неіснуючого користувача")
        void loadUserByUsername_NotFound() {
            when(userRepository.findByUsername("nonexistent")).thenReturn(Optional.empty());

            assertThrows(UsernameNotFoundException.class,
                    () -> userService.loadUserByUsername("nonexistent"));
        }

        @Test
        @DisplayName("Завантаження ADMIN користувача")
        void loadUserByUsername_AdminRole() {
            testUser.setRole(UserRole.ROLE_ADMIN);
            when(userRepository.findByUsername("admin")).thenReturn(Optional.of(testUser));

            UserDetails result = userService.loadUserByUsername("admin");

            assertTrue(result.getAuthorities().stream()
                    .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN")));
        }
    }
}
