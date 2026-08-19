package com.example.bookstore.notification.template;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class EmailTemplateEngine {

    private static final Logger log = LoggerFactory.getLogger(EmailTemplateEngine.class);

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\w+}}");

    private final Map<String, String> templates = new ConcurrentHashMap<>();

    private static final String TEMPLATE_DIR = "templates/email/";
    private static final String EXTENSION = ".txt";

    private static final String[] TEMPLATE_NAMES = {
        "order-confirmation",
        "order-status-changed",
        "payment-success",
        "payment-failed",
        "refund-notification"
    };

    @PostConstruct
    public void init() {
        for (String name : TEMPLATE_NAMES) {
            loadTemplate(name);
        }
    }

    private void loadTemplate(String name) {
        try {
            ClassPathResource resource = new ClassPathResource(TEMPLATE_DIR + name + EXTENSION);
            String content = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            templates.put(name, content);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load email template: " + name, e);
        }
    }

    public String render(String templateName, Map<String, Object> data) {
        String template = templates.get(templateName);
        if (template == null) {
            throw new IllegalArgumentException("Template not found: " + templateName);
        }
        String result = template;
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            String value = entry.getValue() != null ? entry.getValue().toString() : "";
            result = result.replace("{{" + entry.getKey() + "}}", value);
        }
        // Neutralize any placeholders whose value was not supplied so a rendered email
        // never leaks raw template syntax to the recipient. Log a warning because a blank
        // field can hide a caller bug (e.g. a missing value in the event payload).
        Matcher unsupplied = PLACEHOLDER.matcher(result);
        if (unsupplied.find()) {
            List<String> missing = new ArrayList<>();
            do {
                missing.add(unsupplied.group());
            } while (unsupplied.find());
            log.warn("Template '{}' rendered with unsupplied placeholder(s): {}; replaced with empty string",
                    templateName, missing);
            result = unsupplied.replaceAll("");
        }
        return result;
    }
}
