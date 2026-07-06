package com.example.bookstore.base;

import com.example.bookstore.config.jwt.JwtUtil;
import com.example.bookstore.model.AppUser;
import com.example.bookstore.model.UserRole;
import com.example.bookstore.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
public abstract class AbstractControllerTest extends AbstractIntegrationTest {

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected JwtUtil jwtUtil;

    @Autowired
    protected UserRepository userRepository;

    @Autowired
    protected PasswordEncoder passwordEncoder;

    @LocalServerPort
    protected int port;

    protected final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    protected static final String TEST_PASSWORD = "Test123456";

    protected AppUser createTestUser(String username, UserRole role) {
        AppUser user = new AppUser();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(TEST_PASSWORD));
        user.setEmail(username + "@test.com");
        user.setRole(role);
        return userRepository.save(user);
    }

    protected String loginAndGetToken(String username) throws Exception {
        String loginJson = """
                {"username": "%s", "password": "%s"}
                """.formatted(username, TEST_PASSWORD);
        String response = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content(loginJson))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response).get("token").asText();
    }

    protected String registerUserAndGetToken(String username) throws Exception {
        createTestUser(username, UserRole.ROLE_USER);
        return loginAndGetToken(username);
    }

    protected String registerAdminAndGetToken(String username) throws Exception {
        createTestUser(username, UserRole.ROLE_ADMIN);
        return loginAndGetToken(username);
    }
}
