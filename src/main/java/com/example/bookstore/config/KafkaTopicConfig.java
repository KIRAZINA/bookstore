package com.example.bookstore.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
@ConditionalOnProperty(value = "spring.kafka.bootstrap-servers")
public class KafkaTopicConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaTopicConfig.class);

    public static final String TOPIC_ORDERS = "orders.events";
    public static final String TOPIC_PAYMENTS = "payments.events";
    public static final String TOPIC_INVENTORY = "inventory.events";
    public static final String TOPIC_NOTIFICATIONS = "notifications.events";
    public static final String TOPIC_DLT = "dlt.events";

    public static final String CONSUMER_GROUP_ORDERS = "orders-consumer";
    public static final String CONSUMER_GROUP_INVENTORY = "inventory-consumer";
    public static final String CONSUMER_GROUP_PAYMENTS = "payments-consumer";
    public static final String CONSUMER_GROUP_NOTIFICATIONS = "notifications-consumer";

    @Bean
    public NewTopic ordersTopic() {
        return TopicBuilder.name(TOPIC_ORDERS)
                .partitions(3)
                .replicas(1)
                .config("retention.ms", String.valueOf(72 * 60 * 60 * 1000L))
                .build();
    }

    @Bean
    public NewTopic paymentsTopic() {
        return TopicBuilder.name(TOPIC_PAYMENTS)
                .partitions(3)
                .replicas(1)
                .config("retention.ms", String.valueOf(72 * 60 * 60 * 1000L))
                .build();
    }

    @Bean
    public NewTopic inventoryTopic() {
        return TopicBuilder.name(TOPIC_INVENTORY)
                .partitions(3)
                .replicas(1)
                .config("retention.ms", String.valueOf(168 * 60 * 60 * 1000L))
                .build();
    }

    @Bean
    public NewTopic notificationsTopic() {
        return TopicBuilder.name(TOPIC_NOTIFICATIONS)
                .partitions(1)
                .replicas(1)
                .config("retention.ms", String.valueOf(24 * 60 * 60 * 1000L))
                .build();
    }

    @Bean
    public NewTopic deadLetterTopic() {
        return TopicBuilder.name(TOPIC_DLT)
                .partitions(1)
                .replicas(1)
                .config("retention.ms", String.valueOf(7 * 24 * 60 * 60 * 1000L))
                .build();
    }
}
