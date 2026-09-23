package org.yuktisetu.adminservice.jd;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.yuktisetu.adminservice.dto.ParsedJdResponse;
import org.yuktisetu.drive.model.JobType;
import org.yuktisetu.drive.model.WorkMode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a pasted job description into the fields a drive needs.
 *
 * Deliberately deterministic -- no model, no network, no queue. Company JDs for
 * this cohort arrive in a handful of recurring shapes, and when a coordinator
 * asks why the bond came out as 24 months the answer is a rule someone can read
 * rather than a model nobody can interrogate. It also costs nothing to run, so
 * a coordinator can paste, look, edit the text and paste again.
 *
 * The parser NEVER writes. It returns proposals plus the evidence for each, and
 * a human confirms field by field. That is not politeness: a JD saying "no
 * service agreement" that is read as a 24-month bond, published to 2500
 * students, is a correction the TnP has to make in public.
 *
 * Accuracy is not the design goal -- reviewability is. A field the parser is not
 * confident about is left null rather than guessed, because an empty box a
 * coordinator fills in is cheaper than a wrong value they have to notice first.
 *
 * Structure: split into sections by heading, pull list-shaped fields from the
 * section they belong to, and pull scalars (CTC, bond, openings) from the whole
 * text since they are as likely to sit in a sentence as under a heading.
 */
@Slf4j
@Component
public class JdParser {

    // ------------------------------------------------------------- headings

    /** Heading keyword alternatives -> the logical section they open. First match wins. */
    private static final List<Map.Entry<String, String>> HEADING_WORDS = List.of(
            Map.entry("key responsibilities|responsibilities|what you.ll do|what you will do|"
                    + "roles and responsibilities|role & responsibilities|job responsibilities|duties", "responsibilities"),
            Map.entry("preferred skills|preferred qualifications|good to have|nice to have|"
                    + "desirable|bonus points|plus points", "preferred"),
            Map.entry("required skills|must have|mandatory skills|key skills|technical skills|"
                    + "skills required|requirements|qualifications|eligibility criteria|who we.re looking for|"
                    + "what we.re looking for|skills", "mandatory"),
            Map.entry("location|locations|job location|work location|base location|place of posting", "location"),
            Map.entry("compensation|salary|ctc|package|remuneration|stipend", "compensation"),
            Map.entry("about the role|role overview|job description|overview|about the job|summary|"
                    + "position summary|about us|about the company", "description"));

    /** "Required Skills:" alone on its line. */
    private static final List<Map.Entry<Pattern, String>> HEADING_MAP = HEADING_WORDS.stream()
            .map(e -> Map.entry(
                    Pattern.compile("^\\s*(?:[#*\\-\\d.)\\s]*)(" + e.getKey() + ")\\s*:?\\s*$",
                            Pattern.CASE_INSENSITIVE),
                    e.getValue()))
            .toList();

    /**
     * "Work Location: Bengaluru / Hyderabad" -- the heading and its value on one
     * line. Without this the line is not a heading at all, so it never closes the
     * section above it: a "Good to have:" list would run on and swallow the
     * location, the CTC and the bond as if they were preferred skills. That is
     * not a cosmetic bug, it is a JD's compensation appearing in the skills box.
     */
    private static final List<Map.Entry<Pattern, String>> INLINE_HEADING_MAP = HEADING_WORDS.stream()
            .map(e -> Map.entry(
                    Pattern.compile("^\\s*(?:[#*\\-\\d.)\\s]*)(" + e.getKey() + ")\\s*:\\s*(\\S.*)$",
                            Pattern.CASE_INSENSITIVE),
                    e.getValue()))
            .toList();

    private static final Pattern BULLET =
            Pattern.compile("^\\s*(?:[-*•▪●◦–—·>]|\\d+[.)])\\s+(.*)$");

    // -------------------------------------------------------------- scalars

    /**
     * Indian money, in the shapes JDs actually use: "6.5 LPA", "₹6,50,000",
     * "Rs. 12 lakhs", "INR 8L". The lakh/LPA suffix is what makes this awkward --
     * "6.5" and "650000" are the same package written two ways, and a parser
     * that misses the suffix is out by a factor of 100,000.
     */
    private static final Pattern MONEY = Pattern.compile(
            "(?:(?:₹|rs\\.?|inr)\\s*)?"
          + "(\\d{1,3}(?:,\\d{2,3})*(?:\\.\\d+)?|\\d+(?:\\.\\d+)?)"
          + "\\s*(lpa|lakhs?|lacs?|l\\b|cr|crores?|k\\b)?",
            Pattern.CASE_INSENSITIVE);

    /** A range: "5 - 7 LPA", "6.5 to 8 LPA", "between 4 and 6 lakhs". */
    private static final Pattern MONEY_RANGE = Pattern.compile(
            "(?:(?:₹|rs\\.?|inr)\\s*)?(\\d+(?:\\.\\d+)?)\\s*(lpa|lakhs?|lacs?|l\\b)?\\s*"
          + "(?:-|–|—|to|and)\\s*"
          + "(?:(?:₹|rs\\.?|inr)\\s*)?(\\d+(?:\\.\\d+)?)\\s*(lpa|lakhs?|lacs?|l\\b)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern CTC_LINE = Pattern.compile(
            "(?:ctc|salary|package|compensation|remuneration|annual\\s+pay)\\b[^\\n]{0,80}",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern STIPEND_LINE = Pattern.compile(
            "stipend[^\\n]{0,80}", Pattern.CASE_INSENSITIVE);

    private static final Pattern INTERNSHIP_MONTHS = Pattern.compile(
            "(?:internship\\s+(?:duration|period|tenure)|duration\\s+of\\s+internship|internship)"
          + "[^\\n]{0,30}?(\\d{1,2})\\s*(month|months|mos)\\b"
          + "|(\\d{1,2})\\s*(?:month|months)\\s+internship",
            Pattern.CASE_INSENSITIVE);

    /**
     * Bond is the highest-stakes number in a JD and the easiest to get wrong,
     * because "no bond" and "2 year bond" differ by one word. The negation is
     * checked first and wins outright.
     */
    private static final Pattern BOND_NEGATED = Pattern.compile(
            "\\b(?:no|without|nil|zero)\\s+(?:service\\s+)?(?:bond|agreement)"
          + "|bond\\s*[:\\-]?\\s*(?:no|none|nil|not\\s+applicable|n/?a)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern BOND = Pattern.compile(
            "(?:bond|service\\s+agreement|service\\s+commitment)[^\\n]{0,40}?"
          + "(\\d{1,2})\\s*(year|years|yr|yrs|month|months)\\b"
          + "|(\\d{1,2})\\s*(year|years|yr|yrs|month|months)\\s+(?:service\\s+)?(?:bond|agreement)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern VACANCIES = Pattern.compile(
            "(?:(\\d{1,4})\\s*(?:openings?|positions?|vacancies|vacancy|seats?|hires?))"
          + "|(?:(?:no\\.?|number)\\s+of\\s+(?:openings?|positions?|vacancies|hires?)\\s*[:\\-]?\\s*(\\d{1,4}))",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern REMOTE = Pattern.compile(
            "\\b(?:fully\\s+)?remote\\b|work\\s+from\\s+home\\b|\\bwfh\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern HYBRID = Pattern.compile("\\bhybrid\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern ONSITE = Pattern.compile(
            "\\bon[-\\s]?site\\b|\\bin[-\\s]?office\\b|work\\s+from\\s+office\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern PPO = Pattern.compile(
            "\\bppo\\b|pre[-\\s]?placement\\s+offer|full[-\\s]?time\\s+offer\\s+(?:on|after|upon)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern INTERNSHIP_WORD = Pattern.compile(
            "\\bintern(?:ship)?\\b", Pattern.CASE_INSENSITIVE);

    /** Known city names keep "Pune, Maharashtra" from being read as two cities. */
    private static final Map<String, String> CITY_STATE = Map.ofEntries(
            Map.entry("pune", "Maharashtra"), Map.entry("mumbai", "Maharashtra"),
            Map.entry("nagpur", "Maharashtra"), Map.entry("nashik", "Maharashtra"),
            Map.entry("bengaluru", "Karnataka"), Map.entry("bangalore", "Karnataka"),
            Map.entry("mysuru", "Karnataka"), Map.entry("hyderabad", "Telangana"),
            Map.entry("chennai", "Tamil Nadu"), Map.entry("coimbatore", "Tamil Nadu"),
            Map.entry("delhi", "Delhi"), Map.entry("new delhi", "Delhi"),
            Map.entry("noida", "Uttar Pradesh"), Map.entry("gurugram", "Haryana"),
            Map.entry("gurgaon", "Haryana"), Map.entry("kolkata", "West Bengal"),
            Map.entry("ahmedabad", "Gujarat"), Map.entry("indore", "Madhya Pradesh"),
            Map.entry("jaipur", "Rajasthan"), Map.entry("kochi", "Kerala"),
            Map.entry("thiruvananthapuram", "Kerala"), Map.entry("chandigarh", "Chandigarh"),
            Map.entry("bhubaneswar", "Odisha"));

    // ----------------------------------------------------------------- parse

    public ParsedJdResponse parse(String rawText) {
        String text = rawText == null ? "" : rawText.replace("\r\n", "\n").replace('\r', '\n');
        List<Evidence> evidence = new ArrayList<>();

        Map<String, List<String>> sections = splitIntoSections(text);

        String responsibilities = joinLines(sections.get("responsibilities"));
        if (responsibilities != null) {
            evidence.add(new Evidence("responsibilities",
                    firstLineOf(responsibilities), "responsibilities"));
        }

        List<String> mandatory = extractSkills(sections.get("mandatory"));
        List<String> preferred = extractSkills(sections.get("preferred"));
        // A JD with one undifferentiated skills list is the common case; leaving
        // it out entirely would be worse than proposing it as mandatory, which a
        // coordinator can demote in one click.
        mandatory.forEach(s -> evidence.add(new Evidence("mandatorySkills", s, "mandatory")));
        preferred.forEach(s -> evidence.add(new Evidence("preferredSkills", s, "preferred")));

        List<ParsedJdResponse.LocationSuggestion> locations = extractLocations(sections.get("location"), text, evidence);

        String description = joinLines(sections.get("description"));
        if (description == null) {
            description = leadParagraph(text);
            if (description != null) evidence.add(new Evidence("description", firstLineOf(description), "(body)"));
        } else {
            evidence.add(new Evidence("description", firstLineOf(description), "description"));
        }

        BigDecimal[] ctc = extractCtc(text, evidence);
        BigDecimal stipend = extractStipend(text, evidence);
        Integer internshipMonths = extractInternshipMonths(text, evidence);
        Integer bondMonths = extractBondMonths(text, evidence);
        Integer vacancies = extractVacancies(text, evidence);
        WorkMode workMode = extractWorkMode(text, evidence);
        Boolean ppo = PPO.matcher(text).find() ? Boolean.TRUE : null;
        if (Boolean.TRUE.equals(ppo)) evidence.add(new Evidence("ppoPossible", matchOf(PPO, text), "(body)"));

        JobType jobType = extractJobType(text, internshipMonths, stipend, evidence);

        ParsedJdResponse parsed = new ParsedJdResponse(
                null,                       // title is never guessed -- see extractJobType's note
                description, responsibilities,
                mandatory, preferred, locations,
                ctc[0], ctc[1], stipend,
                internshipMonths, bondMonths, vacancies,
                workMode, jobType, ppo,
                evidence.stream().map(e -> new ParsedJdResponse.Evidence(e.field, e.matched, e.section)).toList(),
                unusedSections(sections));

        log.info("Parsed JD: {} chars -> {} fields populated, {} evidence entries",
                text.length(), populatedCount(parsed), evidence.size());
        return parsed;
    }

    // ---------------------------------------------------------- sectioning

    /**
     * Splits the text at recognised headings. Anything before the first heading
     * is kept as "(lead)" so a JD written as flowing prose still yields a
     * description.
     */
    private Map<String, List<String>> splitIntoSections(String text) {
        Map<String, List<String>> sections = new LinkedHashMap<>();
        String current = "(lead)";
        sections.put(current, new ArrayList<>());

        for (String line : text.split("\n")) {
            Heading heading = headingOf(line);
            if (heading != null) {
                current = heading.section();
                sections.computeIfAbsent(current, k -> new ArrayList<>());
                // "Location: Pune" opens the section AND is its only content.
                if (heading.inlineValue() != null) {
                    sections.get(current).add(heading.inlineValue());
                }
            } else {
                sections.get(current).add(line);
            }
        }
        return sections;
    }

    private record Heading(String section, String inlineValue) {}

    private Heading headingOf(String line) {
        String trimmed = line.trim();
        if (trimmed.isEmpty()) return null;

        // A bare heading is short and stands alone. Without the length bound, a
        // sentence mentioning "responsibilities" mid-paragraph would silently
        // swallow everything after it into the wrong section.
        if (trimmed.length() <= 60) {
            for (Map.Entry<Pattern, String> entry : HEADING_MAP) {
                if (entry.getKey().matcher(trimmed).matches()) return new Heading(entry.getValue(), null);
            }
        }
        // An inline heading may be longer, because its value is on the same line.
        if (trimmed.length() <= 200) {
            for (Map.Entry<Pattern, String> entry : INLINE_HEADING_MAP) {
                Matcher m = entry.getKey().matcher(trimmed);
                if (m.matches()) return new Heading(entry.getValue(), m.group(2).trim());
            }
        }
        return null;
    }

    private String headingFor(String line) {
        Heading h = headingOf(line);
        return h == null ? null : h.section();
    }

    private List<String> unusedSections(Map<String, List<String>> sections) {
        return sections.entrySet().stream()
                .filter(e -> !e.getKey().equals("(lead)"))
                .filter(e -> e.getValue().stream().anyMatch(l -> !l.isBlank()))
                .filter(e -> List.of("compensation").contains(e.getKey()))
                .map(Map.Entry::getKey)
                .toList();
    }

    // ------------------------------------------------------------ extractors

    private List<String> extractSkills(List<String> lines) {
        if (lines == null) return new ArrayList<>();
        LinkedHashSet<String> skills = new LinkedHashSet<>();

        for (String line : lines) {
            Matcher bullet = BULLET.matcher(line);
            String content = bullet.matches() ? bullet.group(1) : line;
            if (content.isBlank()) continue;

            // A bullet is either one skill or a comma-separated run of them.
            // Anything sentence-length is prose about the role, not a skill.
            if (content.length() > 120) continue;
            for (String piece : content.split("[,;/]|\\band\\b")) {
                String skill = cleanSkill(piece);
                if (skill != null) skills.add(skill);
            }
        }
        return new ArrayList<>(skills);
    }

    private String cleanSkill(String raw) {
        String s = raw.trim().replaceAll("[.;:]+$", "").trim();

        // Stripped in a loop, not once. JDs stack these -- "Strong knowledge of
        // Data Structures" is an intensifier, then a noun, then a preposition,
        // and a single pass leaves "knowledge of Data Structures" behind as the
        // skill name. Each pass removes one layer until nothing more matches.
        String previous;
        do {
            previous = s;
            s = s.replaceAll("(?i)^(?:strong|good|excellent|solid|deep|basic|working|sound|"
                    + "hands[- ]on|prior|proven|demonstrable)\\s+", "");
            s = s.replaceAll("(?i)^(?:proficiency|proficient|experience|experienced|knowledge|"
                    + "familiarity|familiar|understanding|exposure|expertise|command)\\s+", "");
            s = s.replaceAll("(?i)^(?:in|with|of|on|to|using|the|a|an)\\s+", "");
            s = s.trim();
        } while (!s.equals(previous));
        if (s.length() < 2 || s.length() > 48) return null;
        // Drop fragments that are clearly prose rather than a named skill.
        if (s.split("\\s+").length > 5) return null;
        if (s.matches("(?i)^(?:etc|and|or|the|a|an|with|in|of|strong|good)$")) return null;
        return s;
    }

    private List<ParsedJdResponse.LocationSuggestion> extractLocations(
            List<String> sectionLines, String wholeText, List<Evidence> evidence) {

        LinkedHashSet<String> cities = new LinkedHashSet<>();
        String source = sectionLines != null ? String.join("\n", sectionLines) : wholeText;
        String section = sectionLines != null ? "location" : "(body)";

        String lower = source.toLowerCase();
        for (String city : CITY_STATE.keySet()) {
            if (Pattern.compile("\\b" + Pattern.quote(city) + "\\b").matcher(lower).find()) {
                cities.add(city);
            }
        }

        List<ParsedJdResponse.LocationSuggestion> out = new ArrayList<>();
        for (String city : cities) {
            String display = Arrays.stream(city.split(" "))
                    .map(w -> Character.toUpperCase(w.charAt(0)) + w.substring(1))
                    .reduce((a, b) -> a + " " + b).orElse(city);
            out.add(new ParsedJdResponse.LocationSuggestion(display, CITY_STATE.get(city), "India"));
            evidence.add(new Evidence("locations", display, section));
        }
        return out;
    }

    /** @return [ctcMin, ctcMax], either or both possibly null. */
    private BigDecimal[] extractCtc(String text, List<Evidence> evidence) {
        Matcher line = CTC_LINE.matcher(text);
        while (line.find()) {
            String candidate = line.group();
            if (candidate.toLowerCase().contains("stipend")) continue;

            Matcher range = MONEY_RANGE.matcher(candidate);
            if (range.find()) {
                // "5 - 7 LPA" leaves the first number's unit implicit; it takes
                // the second's, or 5 becomes five rupees.
                String unit = range.group(4);
                BigDecimal min = toRupees(range.group(1), range.group(2) != null ? range.group(2) : unit);
                BigDecimal max = toRupees(range.group(3), unit);
                if (min != null && max != null) {
                    evidence.add(new Evidence("ctcMin", range.group(), "compensation"));
                    evidence.add(new Evidence("ctcMax", range.group(), "compensation"));
                    return new BigDecimal[]{min, max};
                }
            }

            Matcher single = MONEY.matcher(candidate);
            while (single.find()) {
                BigDecimal value = toRupees(single.group(1), single.group(2));
                if (value != null && value.compareTo(BigDecimal.valueOf(10000)) >= 0) {
                    // A fixed package is genuinely both ends of the range, and
                    // the uplift rule needs a max to compare against.
                    evidence.add(new Evidence("ctcMin", single.group().trim(), "compensation"));
                    evidence.add(new Evidence("ctcMax", single.group().trim() + " (single figure)", "compensation"));
                    return new BigDecimal[]{value, value};
                }
            }
        }
        return new BigDecimal[]{null, null};
    }

    private BigDecimal extractStipend(String text, List<Evidence> evidence) {
        Matcher line = STIPEND_LINE.matcher(text);
        while (line.find()) {
            Matcher money = MONEY.matcher(line.group());
            while (money.find()) {
                BigDecimal value = toRupees(money.group(1), money.group(2));
                // Monthly stipends sit in the thousands; anything smaller is a
                // duration or a count that happened to share the line.
                if (value != null && value.compareTo(BigDecimal.valueOf(1000)) >= 0
                        && value.compareTo(BigDecimal.valueOf(500000)) <= 0) {
                    evidence.add(new Evidence("stipendAmount", money.group().trim(), "compensation"));
                    return value;
                }
            }
        }
        return null;
    }

    private Integer extractInternshipMonths(String text, List<Evidence> evidence) {
        Matcher m = INTERNSHIP_MONTHS.matcher(text);
        if (m.find()) {
            String months = m.group(1) != null ? m.group(1) : m.group(3);
            if (months != null) {
                evidence.add(new Evidence("internshipDurationMonths", m.group().trim(), "(body)"));
                return Integer.parseInt(months);
            }
        }
        return null;
    }

    private Integer extractBondMonths(String text, List<Evidence> evidence) {
        if (BOND_NEGATED.matcher(text).find()) {
            // Explicitly absent is not the same as unknown, and it is the answer
            // students ask about most. Recorded as zero so the review screen can
            // show "no bond" rather than an empty box.
            evidence.add(new Evidence("bondMonths", matchOf(BOND_NEGATED, text), "(body)"));
            return 0;
        }
        Matcher m = BOND.matcher(text);
        if (m.find()) {
            String amount = m.group(1) != null ? m.group(1) : m.group(3);
            String unit = m.group(1) != null ? m.group(2) : m.group(4);
            if (amount != null && unit != null) {
                int n = Integer.parseInt(amount);
                int months = unit.toLowerCase().startsWith("y") ? n * 12 : n;
                evidence.add(new Evidence("bondMonths", m.group().trim(), "(body)"));
                return months;
            }
        }
        return null;
    }

    private Integer extractVacancies(String text, List<Evidence> evidence) {
        Matcher m = VACANCIES.matcher(text);
        if (m.find()) {
            String n = m.group(1) != null ? m.group(1) : m.group(2);
            if (n != null) {
                evidence.add(new Evidence("vacancyCount", m.group().trim(), "(body)"));
                return Integer.parseInt(n);
            }
        }
        return null;
    }

    private WorkMode extractWorkMode(String text, List<Evidence> evidence) {
        // Hybrid first: "hybrid - 3 days in office" mentions onsite too, and the
        // more specific claim is the true one.
        if (HYBRID.matcher(text).find()) {
            evidence.add(new Evidence("workMode", matchOf(HYBRID, text), "(body)"));
            return WorkMode.HYBRID;
        }
        if (REMOTE.matcher(text).find()) {
            evidence.add(new Evidence("workMode", matchOf(REMOTE, text), "(body)"));
            return WorkMode.REMOTE;
        }
        if (ONSITE.matcher(text).find()) {
            evidence.add(new Evidence("workMode", matchOf(ONSITE, text), "(body)"));
            return WorkMode.ONSITE;
        }
        return null;
    }

    /**
     * Title is deliberately never guessed. The first line of a JD is as often
     * the company name, a document header or a requisition id as it is the role,
     * and a wrong title is the one field a coordinator is least likely to
     * re-read before publishing -- it is what the drive is called everywhere.
     */
    private JobType extractJobType(String text, Integer internshipMonths,
                                   BigDecimal stipend, List<Evidence> evidence) {
        boolean internship = INTERNSHIP_WORD.matcher(text).find() || internshipMonths != null || stipend != null;
        if (!internship) return null;

        JobType type = PPO.matcher(text).find() ? JobType.INTERNSHIP_WITH_PPO : JobType.INTERNSHIP;
        evidence.add(new Evidence("jobType", type.name(),
                internshipMonths != null ? "(duration stated)" : "(body)"));
        return type;
    }

    // ------------------------------------------------------------- helpers

    /**
     * Normalises an Indian money figure to rupees. The suffix carries almost all
     * the meaning: "6.5" with "LPA" is 650000, and without it is 6.5.
     */
    private BigDecimal toRupees(String number, String suffix) {
        if (number == null) return null;
        BigDecimal value;
        try {
            value = new BigDecimal(number.replace(",", ""));
        } catch (NumberFormatException e) {
            return null;
        }
        if (suffix == null) return value.setScale(2, RoundingMode.HALF_UP);

        String s = suffix.toLowerCase();
        BigDecimal multiplier =
                s.startsWith("cr") || s.startsWith("crore") ? BigDecimal.valueOf(10_000_000)
              : s.startsWith("lpa") || s.startsWith("lakh") || s.startsWith("lac") || s.equals("l") ? BigDecimal.valueOf(100_000)
              : s.equals("k") ? BigDecimal.valueOf(1_000)
              : BigDecimal.ONE;
        return value.multiply(multiplier).setScale(2, RoundingMode.HALF_UP);
    }

    private String joinLines(List<String> lines) {
        if (lines == null) return null;
        String joined = String.join("\n", lines).strip();
        return joined.isEmpty() ? null : joined;
    }

    /** The prose before any heading, which is where a description usually lives. */
    private String leadParagraph(String text) {
        StringBuilder lead = new StringBuilder();
        for (String line : text.split("\n")) {
            if (headingFor(line) != null) break;
            if (!line.isBlank()) lead.append(line.strip()).append("\n");
            else if (!lead.isEmpty()) break;
        }
        String out = lead.toString().strip();
        return out.isEmpty() ? null : out;
    }

    private String firstLineOf(String block) {
        String first = block.lines().findFirst().orElse("").strip();
        return first.length() > 80 ? first.substring(0, 77) + "..." : first;
    }

    private String matchOf(Pattern p, String text) {
        Matcher m = p.matcher(text);
        return m.find() ? m.group().trim() : "";
    }

    private int populatedCount(ParsedJdResponse p) {
        int n = 0;
        if (p.description() != null) n++;
        if (p.responsibilities() != null) n++;
        if (!p.mandatorySkills().isEmpty()) n++;
        if (!p.preferredSkills().isEmpty()) n++;
        if (!p.locations().isEmpty()) n++;
        if (p.ctcMin() != null) n++;
        if (p.stipendAmount() != null) n++;
        if (p.internshipDurationMonths() != null) n++;
        if (p.bondMonths() != null) n++;
        if (p.vacancyCount() != null) n++;
        if (p.workMode() != null) n++;
        if (p.jobType() != null) n++;
        return n;
    }

    /** Internal mirror of the response record, so evidence can be gathered before the response exists. */
    private record Evidence(String field, String matched, String section) {}
}
