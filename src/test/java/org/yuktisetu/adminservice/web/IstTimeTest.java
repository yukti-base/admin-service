package org.yuktisetu.adminservice.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.yuktisetu.identity.db.User;
import org.yuktisetu.identity.model.UserStatus;
import org.yuktisetu.identity.repository.UserRepository;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Time is IST end to end: no UTC wall-clock hiding in a column, no "Z" in the API,
 * nothing to convert before reading it.
 */
@SpringBootTest
@ActiveProfiles("test")
class IstTimeTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @Autowired JsonMapper mapper;
    @Autowired UserRepository userRepository;
    @Autowired JdbcTemplate jdbc;

    @Test
    @DisplayName("the JVM runs in IST whatever zone the host machine is set to")
    void jvmIsIst() {
        assertEquals("Asia/Kolkata", TimeZone.getDefault().getID());
    }

    @Test
    @DisplayName("the API writes both Instant and Date in IST (+05:30), never UTC 'Z'")
    void apiWritesIst() {
        Instant i = Instant.parse("2026-10-19T18:06:04Z");           // = 23:36:04 IST
        assertEquals("{\"i\":\"2026-10-19T23:36:04+05:30\"}", mapper.writeValueAsString(Map.of("i", i)));
        assertEquals("{\"d\":\"2026-10-19T23:36:04.000+05:30\"}", mapper.writeValueAsString(Map.of("d", Date.from(i))));
    }

    @Test
    @DisplayName("an Instant written by the API reads back as the same moment")
    void instantRoundTrips() {
        Instant i = Instant.parse("2026-10-19T18:06:04Z");
        String json = mapper.writeValueAsString(Map.of("i", i));
        Instant back = Instant.parse(java.time.OffsetDateTime.parse(
                json.replaceAll(".*\"i\":\"([^\"]+)\".*", "$1")).toInstant().toString());
        assertEquals(i, back);
    }

    /**
     * users.created_at is TIMESTAMP WITHOUT TIME ZONE. With the old
     * hibernate.jdbc.time_zone=UTC it held UTC wall-clock time, so a row created at
     * 14:14 IST read 08:44 when you looked at it.
     */
    @Test
    @DisplayName("a timestamp column written from a Date holds the IST wall-clock time")
    void timestampColumnsHoldIstWallClock() {
        Date now = new Date();
        User u = userRepository.save(User.builder()
                .email("ist-" + UUID.randomUUID() + "@example.test").phone("5" + (long) (Math.random() * 1_000_000_000L + 1_000_000_000L))
                .password("x").firstName("Ist").lastName("Clock").status(UserStatus.ACTIVE)
                .createdAt(now).updatedAt(now).isDeleted(false).build());

        LocalDateTime stored = jdbc.queryForObject(
                "select created_at from users where id = ?", LocalDateTime.class, u.getId());
        LocalDateTime istNow = LocalDateTime.now(IST);

        assertTrue(Math.abs(Duration.between(stored, istNow).toMinutes()) <= 2,
                "stored " + stored + " but the IST clock says " + istNow
                        + " -- the column must hold what a wall clock in India shows");
    }

    @Test
    @DisplayName("the API accepts an IST-offset time from the browser and reads it as the right moment")
    void apiAcceptsIstOffsetInput() {
        Instant fromIst = mapper.readValue("\"2026-10-20T17:30:00+05:30\"", Instant.class);
        assertEquals(Instant.parse("2026-10-20T12:00:00Z"), fromIst);
        assertEquals(fromIst, mapper.readValue("\"2026-10-20T12:00:00Z\"", Instant.class), "UTC input still works");
    }
}
