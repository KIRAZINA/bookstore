package com.example.bookstore.service;

import com.example.bookstore.model.AppUser;
import com.example.bookstore.model.UserRole;
import com.example.bookstore.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class UserService implements UserDetailsService {
    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    private final UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public AppUser registerUser(AppUser user) {
        // Проверка на существование перед сохранением
        if (userRepository.findByUsername(user.getUsername()).isPresent()) {
            throw new RuntimeException("User already exists");
        }
        user.setPassword(passwordEncoder.encode(user.getPassword())); // Хэшируем здесь
        user.setRole(UserRole.ROLE_USER);  // Enum вместо String
        return userRepository.save(user);
    }

    public Optional<AppUser> findByUsername(String username) {
        log.info("UserService: Querying DB for username = {}", username);  // Logger вместо System.out
        return userRepository.findByUsername(username);
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        AppUser user = findByUsername(username).orElseThrow(() -> new UsernameNotFoundException("User not found"));
        // Извлечение роли без "ROLE_" через switch (enum-safe)
        String roleWithoutPrefix;
        switch (user.getRole()) {
            case ROLE_USER -> roleWithoutPrefix = "USER";
            case ROLE_ADMIN -> roleWithoutPrefix = "ADMIN";
            default -> roleWithoutPrefix = "USER";  // fallback
        }
        return org.springframework.security.core.userdetails.User
                .withUsername(user.getUsername())
                .password(user.getPassword())
                .roles(roleWithoutPrefix)
                .build();
    }
}