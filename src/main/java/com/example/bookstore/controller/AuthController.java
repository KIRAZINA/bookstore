package com.example.bookstore.controller;

import com.example.bookstore.controller.dto.RegisterRequest;
import com.example.bookstore.controller.dto.RegisterResponse;
import com.example.bookstore.model.AppUser;
import com.example.bookstore.service.UserService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final UserService userService;

    public AuthController(UserService userService) {
        this.userService = userService;
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

    /**
     * Login is handled by Spring Security formLogin filter at /api/auth/login
     * This endpoint just returns 200 if user is authenticated
     */
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
