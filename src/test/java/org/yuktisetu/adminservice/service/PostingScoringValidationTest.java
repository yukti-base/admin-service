package org.yuktisetu.adminservice.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yuktisetu.adminservice.exception.AdminExceptions;
import org.yuktisetu.drive.model.ScoringFactor;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Weights are whole numbers 0-5 (decided 2026-10-27); the sliders cannot send anything else. */
class PostingScoringValidationTest {

    private final PostingScoringService service = new PostingScoringService(null, null, null);

    private static Map<ScoringFactor, BigDecimal> weights(String academic, String coding) {
        Map<ScoringFactor, BigDecimal> w = new EnumMap<>(ScoringFactor.class);
        for (ScoringFactor f : ScoringFactor.values()) w.put(f, BigDecimal.ZERO);
        w.put(ScoringFactor.ACADEMIC_PERFORMANCE, new BigDecimal(academic));
        w.put(ScoringFactor.CODING_PROFICIENCY, new BigDecimal(coding));
        return w;
    }

    @Test
    @DisplayName("whole numbers 0 to 5 are accepted, including 5.0 written with a decimal")
    void acceptsTheScale() {
        assertDoesNotThrow(() -> service.validate(weights("5", "0"), BigDecimal.ZERO, Map.of()));
        assertDoesNotThrow(() -> service.validate(weights("3", "1"), BigDecimal.ZERO, Map.of()));
        assertDoesNotThrow(() -> service.validate(weights("5.0", "2.00"), BigDecimal.ZERO, Map.of()));
    }

    @Test
    @DisplayName("above 5, fractions and negatives are refused with a readable message")
    void refusesOutsideTheScale() {
        var e = assertThrows(AdminExceptions.InvalidRequestException.class,
                () -> service.validate(weights("6", "1"), BigDecimal.ZERO, Map.of()));
        assertTrue(e.getMessage().contains("whole number from 0 to 5"), e.getMessage());
        assertThrows(AdminExceptions.InvalidRequestException.class,
                () -> service.validate(weights("2.5", "1"), BigDecimal.ZERO, Map.of()));
        assertThrows(AdminExceptions.InvalidRequestException.class,
                () -> service.validate(weights("-1", "1"), BigDecimal.ZERO, Map.of()));
    }

    @Test
    @DisplayName("all zero is still refused: something has to be weighted")
    void refusesAllZero() {
        assertThrows(AdminExceptions.InvalidRequestException.class,
                () -> service.validate(weights("0", "0"), BigDecimal.ZERO, Map.of()));
    }
}
