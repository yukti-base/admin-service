package org.yuktisetu.adminservice.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.yuktisetu.db.OutboxEvent;
import org.yuktisetu.events.EventEnvelope;
import org.yuktisetu.repository.OutboxEventRepository;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.UUID;

/**
 * Writes an event to the transactional outbox.
 *
 * Callers MUST invoke this from inside the same @Transactional method as the
 * business change. That is the entire point: publishing a drive and queueing its
 * notification either both commit or neither does. Calling KafkaTemplate
 * directly instead would mean a committed publish whose notification silently
 * vanished -- the drive is live, students never hear about it, and nothing in
 * the system records that anything went wrong.
 *
 * A separate relay drains PENDING rows to Kafka. Delivery is therefore
 * at-least-once, which is why the consumer-side tables carry per-event
 * uniqueness constraints.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxWriter {

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    public <T> String write(String topic,
                            String eventType,
                            String aggregateType,
                            Object aggregateId,
                            String partitionKey,
                            Long actorUserId,
                            T payload) {

        String eventId = UUID.randomUUID().toString();
        EventEnvelope<T> envelope = new EventEnvelope<>(
                eventId, eventType, "v1", Instant.now(), actorUserId, payload);

        OutboxEvent row = new OutboxEvent();
        row.setEventId(eventId);
        row.setTopic(topic);
        row.setEventType(eventType);
        row.setAggregateType(aggregateType);
        row.setAggregateId(aggregateId == null ? null : String.valueOf(aggregateId));
        row.setPartitionKey(partitionKey);
        row.setPayload(objectMapper.writeValueAsString(envelope));
        // availableAt = now: the relay may pick it up as soon as this transaction
        // commits. It cannot see the row before then, which is what makes the
        // ordering safe without any extra coordination.
        row.setAvailableAt(Instant.now());

        outboxEventRepository.save(row);
        log.info("Outbox event queued: eventId={} type={} topic={} aggregate={}:{}",
                eventId, eventType, topic, aggregateType, aggregateId);
        return eventId;
    }
}
