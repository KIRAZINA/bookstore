package com.example.bookstore.base;

import com.example.bookstore.config.KafkaTopicConfig;
import com.example.bookstore.config.jwt.JwtUtil;
import com.example.bookstore.model.AppUser;
import com.example.bookstore.model.UserRole;
import com.example.bookstore.repository.UserRepository;
import com.example.bookstore.service.payment.DummyPaymentProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.RestTemplate;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@ActiveProfiles({"test", "perf-test"})
@EmbeddedKafka(
        topics = {
                KafkaTopicConfig.TOPIC_ORDERS,
                KafkaTopicConfig.TOPIC_PAYMENTS,
                KafkaTopicConfig.TOPIC_INVENTORY,
                KafkaTopicConfig.TOPIC_NOTIFICATIONS
        },
        partitions = 3,
        controlledShutdown = true,
        bootstrapServersProperty = "spring.kafka.bootstrap-servers"
)
public abstract class AbstractPerformanceTest extends AbstractIntegrationTest {

    protected static final String TEST_PASSWORD = "Test123456";
    protected static final int DEFAULT_WARMUP = Integer.getInteger("perf.warmup", 5);
    protected static final int DEFAULT_ITERATIONS = Integer.getInteger("perf.iterations", 50);

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

    @MockitoBean
    protected DummyPaymentProvider paymentProvider;

    protected RestTemplate restTemplate;
    protected final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    @BeforeEach
    void initRestTemplate() {
        restTemplate = new RestTemplate();
    }

    protected String baseUrl() {
        return "http://localhost:" + port;
    }

    protected AppUser createTestUser(String username, UserRole role) {
        AppUser user = new AppUser();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(TEST_PASSWORD));
        user.setEmail(username + "@perf.test");
        user.setRole(role);
        return userRepository.save(user);
    }

    protected String loginAndGetToken(String username) throws Exception {
        String loginJson = """
                {"username": "%s", "password": "%s"}
                """.formatted(username, TEST_PASSWORD);
        String response = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response).get("token").asText();
    }

    protected HttpHeaders bearerHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    protected HttpEntity<String> jsonEntity(String token, String body) {
        return new HttpEntity<>(body, bearerHeaders(token));
    }

    protected HttpEntity<Void> authEntity(String token) {
        return new HttpEntity<>(bearerHeaders(token));
    }
}
