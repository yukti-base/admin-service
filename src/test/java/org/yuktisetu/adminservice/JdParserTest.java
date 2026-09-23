package org.yuktisetu.adminservice;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yuktisetu.adminservice.dto.ParsedJdResponse;
import org.yuktisetu.adminservice.jd.JdParser;
import org.yuktisetu.drive.model.JobType;
import org.yuktisetu.drive.model.WorkMode;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The JD parser, against the shapes real campus JDs actually arrive in.
 *
 * Pure unit tests -- the parser touches no database, no network and no model, so
 * there is nothing to stand up. That is the point of choosing a deterministic
 * parser: every expectation below is a rule someone can read, and when a
 * coordinator asks why the bond came out as 24 months there is a test naming it.
 *
 * Two assertions matter more than raw coverage. First, that money is scaled
 * correctly -- "6.5 LPA" and "650000" are the same package and being out by a
 * factor of 100,000 on a CTC is the single most damaging thing this can do.
 * Second, that "no bond" is never read as a bond: that sentence is the one
 * students ask about most, and getting it backwards is a correction the TnP has
 * to make in public after 2500 people have seen it.
 */
class JdParserTest {

    private final JdParser parser = new JdParser();

    // A JD in the shape most product companies send.
    private static final String STRUCTURED = """
            Infosys is hiring Systems Engineers for its Pune delivery centre.
            We are looking for graduates who enjoy building reliable software.

            Key Responsibilities:
            - Design and implement backend services
            - Participate in code reviews and design discussions
            - Support production systems on a rotating on-call schedule

            Required Skills:
            - Java, Spring Boot
            - SQL
            - Strong knowledge of Data Structures

            Preferred Skills:
            - Kubernetes
            - Experience with Kafka

            Location: Pune, Maharashtra

            Compensation: CTC 6.5 - 8 LPA
            Service agreement of 2 years applies.
            Number of openings: 40
            This is a hybrid role.
            """;

    // ------------------------------------------------------------- sections

    @Test
    @DisplayName("responsibilities come from their own section, not the whole document")
    void responsibilitiesAreSectioned() {
        ParsedJdResponse p = parser.parse(STRUCTURED);

        assertNotNull(p.responsibilities());
        assertTrue(p.responsibilities().contains("Design and implement backend services"));
        assertTrue(p.responsibilities().contains("rotating on-call"));
        assertFalse(p.responsibilities().contains("Kubernetes"),
                "a preferred skill must not leak into responsibilities");
        assertFalse(p.responsibilities().contains("Infosys is hiring"),
                "the lead paragraph is the description, not a responsibility");
    }

    @Test
    @DisplayName("mandatory and preferred skills land in separate lists")
    void skillsAreSplitByTheirHeading() {
        ParsedJdResponse p = parser.parse(STRUCTURED);

        assertTrue(p.mandatorySkills().contains("Java"));
        assertTrue(p.mandatorySkills().contains("Spring Boot"));
        assertTrue(p.mandatorySkills().contains("SQL"));
        assertTrue(p.preferredSkills().contains("Kubernetes"));
        assertFalse(p.mandatorySkills().contains("Kubernetes"),
                "a preferred skill promoted to mandatory silently narrows who can apply");
    }

    /** "Strong knowledge of Data Structures" is one skill wearing a sentence. */
    @Test
    @DisplayName("filler wording is stripped so the skill itself survives")
    void skillPrefixesAreStripped() {
        ParsedJdResponse p = parser.parse(STRUCTURED);
        assertTrue(p.mandatorySkills().contains("Data Structures"),
                "expected the bare skill, got " + p.mandatorySkills());
        assertTrue(p.preferredSkills().contains("Kafka"),
                "expected 'Kafka' from 'Experience with Kafka', got " + p.preferredSkills());
    }

    @Test
    @DisplayName("the lead paragraph becomes the description when there is no heading for it")
    void leadParagraphBecomesDescription() {
        ParsedJdResponse p = parser.parse(STRUCTURED);
        assertNotNull(p.description());
        assertTrue(p.description().contains("Infosys is hiring"));
        assertFalse(p.description().contains("Design and implement"),
                "the description must stop at the first heading");
    }

    /**
     * Without a length bound on headings, a sentence merely containing the word
     * "responsibilities" would open a section and swallow the rest of the JD.
     */
    @Test
    @DisplayName("a sentence mentioning a heading word mid-paragraph is not treated as a heading")
    void proseIsNotMistakenForAHeading() {
        ParsedJdResponse p = parser.parse("""
                You will share responsibilities with a senior engineer on this team and grow quickly.

                Required Skills:
                - Python
                """);
        assertTrue(p.mandatorySkills().contains("Python"));
        assertNull(p.responsibilities(), "no Responsibilities heading exists in this JD");
    }

    /**
     * Regression. "Work Location: Bengaluru" is a heading with its value on the
     * same line, so it was not recognised as a heading at all -- which meant it
     * never closed the "Good to have:" list above it. The CTC, the bond and the
     * openings count all ended up in the preferred-skills box.
     */
    @Test
    @DisplayName("a heading with its value inline closes the section above it")
    void inlineHeadingClosesThePreviousSection() {
        ParsedJdResponse p = parser.parse("""
                Good to have:
                - Docker
                - Kubernetes

                Work Location: Bengaluru
                CTC: 7 - 11.5 LPA
                No. of openings: 120
                """);

        assertEquals(List.of("Docker", "Kubernetes"), p.preferredSkills(),
                "everything below the location heading leaked into preferred skills");
        assertEquals(1, p.locations().size());
        assertEquals("Bengaluru", p.locations().get(0).city());
        assertEquals(0, new BigDecimal("700000.00").compareTo(p.ctcMin()));
        assertEquals(120, p.vacancyCount());
    }

    @Test
    @DisplayName("an inline heading still yields its own value")
    void inlineHeadingKeepsItsValue() {
        ParsedJdResponse p = parser.parse("Job Location: Pune, Maharashtra");
        assertEquals(1, p.locations().size());
        assertEquals("Pune", p.locations().get(0).city());
    }

    /** "Exposure to Kafka" kept its preposition and proposed "to Kafka" as a skill. */
    @Test
    @DisplayName("a trailing preposition is stripped along with the filler noun")
    void prepositionsAreStrippedToo() {
        ParsedJdResponse p = parser.parse("""
                Preferred Skills:
                - Exposure to Kafka
                - Familiarity with Terraform
                """);
        assertTrue(p.preferredSkills().contains("Kafka"), "got " + p.preferredSkills());
        assertTrue(p.preferredSkills().contains("Terraform"), "got " + p.preferredSkills());
    }

    // ----------------------------------------------------------------- money

    @Test
    @DisplayName("a LPA range scales to rupees at both ends")
    void lpaRangeScalesCorrectly() {
        ParsedJdResponse p = parser.parse(STRUCTURED);
        assertEquals(0, new BigDecimal("650000.00").compareTo(p.ctcMin()), "got " + p.ctcMin());
        assertEquals(0, new BigDecimal("800000.00").compareTo(p.ctcMax()), "got " + p.ctcMax());
    }

    /** "5 - 7 LPA" leaves the first figure's unit implicit; it inherits the second's. */
    @Test
    @DisplayName("the leading figure of a range inherits the trailing unit")
    void leadingFigureInheritsTheUnit() {
        ParsedJdResponse p = parser.parse("CTC: 5 - 7 LPA");
        assertEquals(0, new BigDecimal("500000.00").compareTo(p.ctcMin()),
                "5 must be read as 5 lakh, not 5 rupees -- got " + p.ctcMin());
    }

    @Test
    @DisplayName("a plain rupee figure is taken at face value")
    void plainRupeeFigureIsNotScaled() {
        ParsedJdResponse p = parser.parse("Salary: Rs. 6,50,000 per annum");
        assertEquals(0, new BigDecimal("650000.00").compareTo(p.ctcMin()), "got " + p.ctcMin());
    }

    @Test
    @DisplayName("a single fixed package fills both ends of the range")
    void singleFigureFillsBothEnds() {
        ParsedJdResponse p = parser.parse("Package: 12 lakhs per annum");
        assertEquals(0, new BigDecimal("1200000.00").compareTo(p.ctcMin()));
        assertEquals(0, p.ctcMin().compareTo(p.ctcMax()),
                "the uplift rule compares against ctcMax, so a fixed package must set it");
    }

    @Test
    @DisplayName("a stipend is never mistaken for a CTC")
    void stipendIsNotReadAsCtc() {
        ParsedJdResponse p = parser.parse("Stipend: Rs 25,000 per month during the internship.");
        assertEquals(0, new BigDecimal("25000.00").compareTo(p.stipendAmount()));
        assertNull(p.ctcMin(), "a stipend line must not populate the annual package");
    }

    // ------------------------------------------------------------------ bond

    @Test
    @DisplayName("a bond in years is converted to months")
    void bondYearsBecomeMonths() {
        assertEquals(24, parser.parse(STRUCTURED).bondMonths());
    }

    @Test
    @DisplayName("a bond already stated in months is left alone")
    void bondMonthsStayMonths() {
        assertEquals(18, parser.parse("Candidates sign a bond of 18 months.").bondMonths());
    }

    /** The single most damaging misread available to this parser. */
    @Test
    @DisplayName("'no service bond' is recorded as zero, never as a bond")
    void noBondIsNotABond() {
        assertEquals(0, parser.parse("There is no service bond for this role.").bondMonths());
        assertEquals(0, parser.parse("Bond: None").bondMonths());
        assertEquals(0, parser.parse("This position carries no service agreement.").bondMonths());
    }

    @Test
    @DisplayName("a JD silent about a bond leaves it unknown rather than zero")
    void silenceAboutBondIsNull() {
        assertNull(parser.parse("We are hiring backend engineers in Pune.").bondMonths(),
                "unknown and 'explicitly no bond' are different answers to a student's question");
    }

    // ------------------------------------------------------- other scalars

    @Test
    @DisplayName("openings are read from either phrasing")
    void vacanciesAreRead() {
        assertEquals(40, parser.parse(STRUCTURED).vacancyCount());
        assertEquals(25, parser.parse("We have 25 openings this cycle.").vacancyCount());
    }

    @Test
    @DisplayName("hybrid wins over the office it also mentions")
    void hybridBeatsOnsite() {
        ParsedJdResponse p = parser.parse("Hybrid role - 3 days from the office each week.");
        assertEquals(WorkMode.HYBRID, p.workMode(), "the more specific claim is the true one");
    }

    @Test
    @DisplayName("remote and onsite are recognised on their own")
    void remoteAndOnsiteAreRecognised() {
        assertEquals(WorkMode.REMOTE, parser.parse("This is a fully remote position.").workMode());
        assertEquals(WorkMode.REMOTE, parser.parse("Work from home role.").workMode());
        assertEquals(WorkMode.ONSITE, parser.parse("Candidates work on-site in Pune.").workMode());
    }

    @Test
    @DisplayName("a city is resolved with its state, not split into two locations")
    void locationResolvesCityAndState() {
        ParsedJdResponse p = parser.parse(STRUCTURED);
        assertEquals(1, p.locations().size(), "got " + p.locations());
        assertEquals("Pune", p.locations().get(0).city());
        assertEquals("Maharashtra", p.locations().get(0).state());
        assertEquals("India", p.locations().get(0).country());
    }

    @Test
    @DisplayName("an internship with a PPO is typed as such")
    void internshipWithPpoIsTyped() {
        ParsedJdResponse p = parser.parse("""
                6 months internship with a PPO for strong performers.
                Stipend: 30,000 per month.
                """);
        assertEquals(JobType.INTERNSHIP_WITH_PPO, p.jobType());
        assertEquals(6, p.internshipDurationMonths());
        assertTrue(p.ppoPossible());
    }

    @Test
    @DisplayName("a full-time JD is not typed as an internship")
    void fullTimeIsNotAnInternship() {
        ParsedJdResponse p = parser.parse("Full time Systems Engineer role. CTC 7 LPA.");
        assertNull(p.jobType(), "nothing marks this as an internship, so the field stays for the human");
    }

    // -------------------------------------------------------------- safety

    /**
     * The title is what the drive is called everywhere, and the first line of a
     * JD is as often a company name or a requisition id as it is the role.
     */
    @Test
    @DisplayName("the title is never guessed")
    void titleIsAlwaysNull() {
        assertNull(parser.parse(STRUCTURED).title());
    }

    @Test
    @DisplayName("empty and junk input yield nulls rather than an exception")
    void degradesQuietly() {
        for (String input : new String[]{"", "   ", "\n\n\n", "asdkjfh alskdjf"}) {
            ParsedJdResponse p = assertDoesNotThrow(() -> parser.parse(input));
            assertNull(p.ctcMin());
            assertNull(p.bondMonths());
            assertTrue(p.mandatorySkills().isEmpty());
        }
        assertDoesNotThrow(() -> parser.parse(null));
    }

    /** Every proposal has to be justifiable, or the review screen is theatre. */
    @Test
    @DisplayName("every populated field carries evidence naming where it came from")
    void everyValueIsEvidenced() {
        ParsedJdResponse p = parser.parse(STRUCTURED);

        for (String field : new String[]{"ctcMin", "ctcMax", "bondMonths", "vacancyCount",
                                         "workMode", "locations", "responsibilities"}) {
            assertTrue(p.evidence().stream().anyMatch(e -> e.field().equals(field)),
                    "no evidence recorded for " + field);
        }
        p.evidence().forEach(e -> assertFalse(e.matched().isBlank(),
                "evidence for " + e.field() + " quotes nothing"));
    }
}
