package org.yuktisetu.adminservice.dto;

import org.yuktisetu.drive.model.JobType;
import org.yuktisetu.drive.model.WorkMode;

import java.math.BigDecimal;
import java.util.List;

/**
 * What a parse of a pasted job description produced. Nothing here is persisted.
 *
 * Every field is nullable, and a null means "not found", never "not present in
 * the role". The coordinator reviews and confirms before any of it reaches a
 * posting -- a mis-read CTC or an invented two-year bond goes out to the whole
 * cohort on publish, so the parser proposes and a human disposes.
 *
 * `evidence` is the reason this is reviewable rather than a black box: each
 * entry names the field, the exact substring that produced it, and the section
 * heading it came from. A coordinator can see that ctcMax=800000 came from
 * "6 - 8 LPA" under "Compensation" and accept it in a second, or spot that it
 * came from an unrelated sentence and reject it.
 */
public record ParsedJdResponse(
        String title,
        String description,
        String responsibilities,
        List<String> mandatorySkills,
        List<String> preferredSkills,
        List<LocationSuggestion> locations,
        BigDecimal ctcMin,
        BigDecimal ctcMax,
        BigDecimal stipendAmount,
        Integer internshipDurationMonths,
        Integer bondMonths,
        Integer vacancyCount,
        WorkMode workMode,
        JobType jobType,
        Boolean ppoPossible,
        List<Evidence> evidence,
        /** Headings the parser recognised as sections but had no field for. */
        List<String> unusedSections
) {
    public record LocationSuggestion(String city, String state, String country) {}

    /**
     * @param field    the response field this justifies, e.g. "ctcMax"
     * @param matched  the exact text the value was read from
     * @param section  the heading it sat under, or "(body)" when outside any section
     */
    public record Evidence(String field, String matched, String section) {}
}
