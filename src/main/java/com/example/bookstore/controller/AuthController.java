package com.example.bookstore.controller;

import com.example.bookstore.config.jwt.JwtUtil;
import com.example.bookstore.controller.dto.LoginRequest;
import com.example.bookstore.controller.dto.LoginResponse;
import com.example.bookstore.controller.dto.RegisterRequest;
import com.example.bookstore.controller.dto.RegisterResponse;
import com.example.bookstore.model.AppUser;
import com.example.bookstore.service.UserService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final UserService userService;
    private final AuthenticationManager authenticationManager;
    private final JwtUtil jwtUtil;

    public AuthController(UserService userService, AuthenticationManager authenticationManager, JwtUtil jwtUtil) {
        this.userService = userService;
        this.authenticationManager = authenticationManager;
        this.jwtUtil = jwtUtil;
    }

    @PostMapping("/register")
    public ResponseEntity<RegisterResponse> register(@Valid @RequestBody RegisterRequest request) {
        log.info("Register attempt for username: {}", request.getUsername());
        AppUser user = new AppUser();
        user.setUsername(request.getUsername());
        user.setPassword(request.getPassword());
        user.setEmail(request.getEmail());
        AppUser saved = userService.registerUser(user);

        RegisterResponse response = new RegisterResponse(
            saved.getId(),
            saved.getUsername(),
            saved.getEmail(),
            saved.getRole()
        );
        return ResponseEntity.ok(response);
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest request) {
        log.info("Login attempt for username: {}", request.getUsername());

        try {
            Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.getUsername(), request.getPassword())
            );

            String token = jwtUtil.generateToken(authentication);
            AppUser user = userService.findByUsername(request.getUsername()).orElseThrow();

            LoginResponse response = new LoginResponse(
                token,
                user.getId(),
                user.getUsername(),
                user.getEmail(),
                user.getRole(),
                jwtUtil.getExpirationTime()
            );
            return ResponseEntity.ok(response);
        } catch (BadCredentialsException e) {
            return ResponseEntity.status(401).body("Invalid credentials");
        }
    }

    @GetMapping("/me")
    public ResponseEntity<RegisterResponse> getCurrentUser(@org.springframework.security.core.annotation.AuthenticationPrincipal org.springframework.security.core.userdetails.UserDetails userDetails) {
        if (userDetails == null) {
            return ResponseEntity.status(401).build();
        }
        AppUser user = userService.findByUsername(userDetails.getUsername()).orElseThrow();
        return ResponseEntity.ok(new RegisterResponse(
            user.getId(),
            user.getUsername(),
            user.getEmail(),
            user.getRole()
        ));
    }
}
