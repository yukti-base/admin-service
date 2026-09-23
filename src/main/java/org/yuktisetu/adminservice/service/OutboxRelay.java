package org.yuktisetu.adminservice.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.yuktisetu.messaging.db.OutboxEvent;
import org.yuktisetu.messaging.model.OutboxStatus;
import org.yuktisetu.messaging.repository.OutboxEventRepository;

import java.time.Instant;
import java.util.List;

/**
 * Drains admin-service's outbox to Kafka.
 *
 * This is the half that was missing. OutboxWriter has been writing
 * posting.published rows on every publish since the drive feature shipped, but
 * admin-service had no spring-kafka dependency and no relay, so those rows
 * accumulated as PENDING with nothing able to ship them -- a write-only table.
 * A drive marked notifyOnPublish would never reach anyone, and nothing in the
 * logs said so.
 *
 * Deliberately a copy of drive-service's relay rather than a shared class in
 * core: core may not depend on a DAL, and this needs OutboxEvent. Two short
 * copies beat putting messaging-dal in front of every service in the fleet.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "yuktisetu.kafka.enabled", havingValue = "true")
public class OutboxRelay {

    private static final int BATCH_SIZE = 200;
    private static final int MAX_ATTEMPTS = 10;

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    public OutboxRelay(OutboxEventRepository outboxEventRepository,
                       KafkaTemplate<String, String> kafkaTemplate) {
        this.outboxEventRepository = outboxEventRepository;
        this.kafkaTemplate = kafkaTemplate;
    }

    @Scheduled(fixedDelayString = "${yuktisetu.outbox.relay.fixed-delay-ms:2000}")
    @Transactional
    public void publishPending() {
        List<OutboxEvent> batch = outboxEventRepository.claimBatch(BATCH_SIZE);
        if (batch.isEmpty()) {
            return;
        }
        for (OutboxEvent event : batch) {
            try {
                kafkaTemplate.send(event.getTopic(), event.getPartitionKey(), event.getPayload())
                        .get();
                event.setStatus(OutboxStatus.PUBLISHED);
                event.setPublishedAt(Instant.now());
                event.setLastError(null);
            } catch (Exception e) {
                int attempts = event.getAttempts() + 1;
                event.setAttempts(attempts);
                event.setLastError(e.getClass().getSimpleName() + ": " + e.getMessage());
                if (attempts >= MAX_ATTEMPTS) {
                    event.setStatus(OutboxStatus.FAILED);
                    log.error("Outbox event {} FAILED permanently after {} attempts, topic={}",
                            event.getEventId(), attempts, event.getTopic());
                } else {
                    event.setAvailableAt(Instant.now().plusSeconds((long) Math.pow(2, attempts)));
                    log.warn("Outbox event {} publish failed (attempt {}), retrying: {}",
                            event.getEventId(), attempts, e.getMessage());
                }
            }
        }
        outboxEventRepository.saveAll(batch);
    }
}
