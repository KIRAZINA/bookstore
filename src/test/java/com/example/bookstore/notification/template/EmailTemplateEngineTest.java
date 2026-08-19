package com.example.bookstore.notification.template;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class EmailTemplateEngineTest {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{(\\w+)}}");

    private static final String[] TEMPLATE_NAMES = {
        "order-confirmation",
        "order-status-changed",
        "payment-success",
        "payment-failed",
        "refund-notification"
    };

    private EmailTemplateEngine engine;

    @BeforeEach
    void setUp() {
        engine = new EmailTemplateEngine();
        engine.init();
    }

    @Test
    @DisplayName("Renders every template without leaving unresolved placeholders")
    void rendersAllTemplatesWithoutLeftovers() {
        for (String name : TEMPLATE_NAMES) {
            String raw = engine.render(name, sampleDataFor(name));
            Matcher leftover = PLACEHOLDER.matcher(raw);
            assertFalse(leftover.find(),
                    "Template '" + name + "' still contains unresolved placeholder(s) after render: " + raw);
        }
    }

    @Test
    @DisplayName("Renders values into the correct positions")
    void rendersProvidedValues() {
        Map<String, Object> data = new HashMap<>();
        data.put("orderId", 42);
        data.put("userName", "Alice");
        data.put("totalPrice", 19.99);

        String body = engine.render("order-confirmation", data);

        assertTrue(body.contains("Order Confirmation #42"));
        assertTrue(body.contains("Dear Alice"));
        assertTrue(body.contains("$19.99"));
        assertFalse(body.contains("{{"));
    }

    @Test
    @DisplayName("Renders missing values as empty strings instead of failing")
    void rendersMissingValuesAsEmpty() {
        Map<String, Object> data = new HashMap<>();
        data.put("orderId", 7);

        String body = engine.render("order-confirmation", data);

        assertFalse(body.contains("{{"));
        assertFalse(body.contains("Dear null"));
    }

    @Test
    @DisplayName("Throws when template does not exist")
    void throwsForUnknownTemplate() {
        assertThrows(IllegalArgumentException.class, () -> engine.render("does-not-exist", Map.of()));
    }

    @Test
    @DisplayName("Logs a warning when unsupplied placeholders are neutralized")
    void logsWarningForMissingValues() {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(EmailTemplateEngine.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            engine.render("order-confirmation", Map.of("orderId", 7));

            assertTrue(appender.list.stream().anyMatch(e -> e.getLevel() == Level.WARN
                            && e.getFormattedMessage().contains("unsupplied placeholder")),
                    "Expected a WARN about unsupplied placeholders, got: " + appender.list);
        } finally {
            logger.detachAppender(appender);
        }
    }

    private Map<String, Object> sampleDataFor(String templateName) {
        Map<String, Object> data = new HashMap<>();
        data.put("orderId", 123);
        data.put("userName", "Alice");
        data.put("userEmail", "alice@example.com");
        data.put("totalPrice", 59.99);
        data.put("amount", 49.99);
        data.put("transactionId", "TXN-001");
        data.put("failureReason", "Insufficient funds");
        data.put("previousStatus", "PENDING");
        data.put("newStatus", "PAID");
        return data;
    }
}