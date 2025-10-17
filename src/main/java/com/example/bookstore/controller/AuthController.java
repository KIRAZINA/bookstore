package com.example.bookstore.controller;

import com.example.bookstore.controller.dto.LoginRequest;
import com.example.bookstore.controller.dto.RegisterRequest;
import com.example.bookstore.model.AppUser;
import com.example.bookstore.service.JwtService;
import com.example.bookstore.service.UserService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final UserService userService;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;

    public AuthController(UserService userService, JwtService jwtService, PasswordEncoder passwordEncoder) {
        this.userService = userService;
        this.jwtService = jwtService;
        this.passwordEncoder = passwordEncoder;
    }

    @PostMapping("/register")
    public ResponseEntity<?> register(@Valid @RequestBody RegisterRequest request) {
        log.info("Register attempt for username: {}", request.getUsername());
        AppUser user = new AppUser();
        user.setUsername(request.getUsername());
        user.setPassword(request.getPassword()); // Сервис захэширует
        user.setEmail(request.getEmail());
        AppUser saved = userService.registerUser(user);
        return ResponseEntity.ok(saved);
    }

    @PostMapping("/login")
    public ResponseEntity<String> login(@Valid @RequestBody LoginRequest request) {
        log.info("Login attempt: Searching for user = {}", request.getUsername());

        Optional<AppUser> userOpt = userService.findByUsername(request.getUsername());
        if (userOpt.isEmpty()) {
            log.warn("User not found: {}", request.getUsername());
            return ResponseEntity.status(401).body("Invalid credentials");
        }

        AppUser appUser = userOpt.get();
        if (!passwordEncoder.matches(request.getPassword(), appUser.getPassword())) {
            log.warn("Password mismatch for user: {}", request.getUsername());
            return ResponseEntity.status(401).body("Invalid credentials");
        }

        log.info("Success! Generating token for user: {}", appUser.getUsername());
        String token = jwtService.generateToken(appUser.getUsername());
        return ResponseEntity.ok(token);
    }
}