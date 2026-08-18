package com.example.bookstore.event;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

import org.springframework.data.domain.Persistable;

@Entity
@Table(name = "processed_events")
@IdClass(ProcessedEvent.ProcessedEventId.class)
public class ProcessedEvent implements Persistable<ProcessedEvent.ProcessedEventId> {

    @Id
    @Column(name = "event_id", length = 100)
    private String eventId;

    @Id
    @Column(name = "consumer_group", length = 100)
    private String consumerGroup;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    @Transient
    private boolean newEntity;

    protected ProcessedEvent() {
        this.newEntity = false;
    }

    public ProcessedEvent(String eventId, String consumerGroup) {
        this.eventId = eventId;
        this.consumerGroup = consumerGroup;
        this.processedAt = Instant.now();
        this.newEntity = true;
    }

    @Override
    public ProcessedEventId getId() {
        return new ProcessedEventId(eventId, consumerGroup);
    }

    @Override
    public boolean isNew() {
        return newEntity;
    }

    public String getEventId() { return eventId; }
    public String getConsumerGroup() { return consumerGroup; }
    public Instant getProcessedAt() { return processedAt; }

    public static class ProcessedEventId implements Serializable {
        private String eventId;
        private String consumerGroup;

        /** No-arg constructor required by JPA for @IdClass resolution. */
        public ProcessedEventId() {}

        public ProcessedEventId(String eventId, String consumerGroup) {
            this.eventId = eventId;
            this.consumerGroup = consumerGroup;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof ProcessedEventId that)) {
                return false;
            }
            return Objects.equals(eventId, that.eventId) && Objects.equals(consumerGroup, that.consumerGroup);
        }

        @Override
        public int hashCode() {
            return Objects.hash(eventId, consumerGroup);
        }
    }
}
