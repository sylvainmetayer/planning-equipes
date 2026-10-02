package dev.sylvain.planning.service.analyse;

import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.analyse.PlanningHoursService.HeuresRapport;
import dev.sylvain.planning.service.publication.PlanPublieService;
import dev.sylvain.planning.service.solve.PlanningPersistenceService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * The hours report read from a plan the server holds — the persisted plan or
 * the publication in force — never from one a client sends.
 *
 * <p>The report used to be computed on whatever plan the browser posted, so the
 * payroll file said what one screen held at one moment, not what the database
 * held nor what the animateurs had been sent: nobody could stand behind it. Read
 * here, nothing the working plan does moves the {@link Source#PUBLIE} report.
 * It is not frozen for all that: its seats are, at publication, but they are
 * resolved against today's referential exactly as the espace resolves them — a
 * timeslot that still exists gives its hours of today (a deleted one keeps the
 * hours the snapshot recorded), the breaks and the evening follow today's legal
 * parameters, and an animateur or a stand deleted since drops out. Two calls
 * give the same report as long as neither the publication nor that referential
 * moves.</p>
 *
 * <p>Each reading says which plan it read and when that plan was dated, and the
 * CSV carries the same sentence on its first line: a file read away from the
 * screen has nothing else to say where its figures come from.</p>
 */
@ApplicationScoped
public class PlanningHoursReader {

    /** How the plan's date is printed in the CSV — the same pattern as the PDF footers. */
    private static final DateTimeFormatter DATE_PLAN = DateTimeFormatter.ofPattern("dd/MM/yyyy 'à' HH:mm");

    /** Which plan a report is read from, by the value the {@code source} query parameter carries. */
    public enum Source {

        /** The plan being worked on, as {@code poste_affectation} holds it. */
        PERSISTE,

        /** The last published snapshot — what the animateurs were sent. */
        PUBLIE;

        /** The value on the wire: {@code persiste}, {@code publie}. */
        public String param() {
            return name().toLowerCase(Locale.ROOT);
        }

        /**
         * The source a parameter names; {@code null} or blank for none. An
         * unknown value is refused rather than read as the default: a payroll
         * file computed on another plan than the one asked for is worse than
         * no file.
         */
        public static Optional<Source> parse(String value) {
            if (value == null || value.isBlank()) {
                return Optional.empty();
            }
            for (Source source : values()) {
                if (source.param().equals(value.trim())) {
                    return Optional.of(source);
                }
            }
            throw new BusinessError.Invalid("Source d'heures inconnue : « " + value.trim()
                    + " ». Valeurs admises : « persiste » (plan enregistré) ou « publie » (plan publié).");
        }
    }

    private final PlanningHoursService hoursService;
    private final PlanningPersistenceService persistenceService;
    private final PlanPublieService planPublieService;

    @Inject
    public PlanningHoursReader(
            PlanningHoursService hoursService,
            PlanningPersistenceService persistenceService,
            PlanPublieService planPublieService) {
        this.hoursService = hoursService;
        this.persistenceService = persistenceService;
        this.planPublieService = planPublieService;
    }

    /**
     * The report of the plan {@code source} names; with none named, the
     * publication in force when there is one, the persisted plan otherwise —
     * the figures a payroll should be paid on when they exist, the working
     * ones before.
     *
     * @throws BusinessError.Conflict {@link Source#PUBLIE} asked while this
     *                                edition never published — a state of the
     *                                edition, not a missing resource: an empty
     *                                report would claim nobody was given any hours
     */
    public HoursReading read(Source source) {
        boolean publicationAvailable;
        if (source == Source.PERSISTE) {
            publicationAvailable = !planPublieService.jamaisPublie();
        } else {
            Optional<PlanPublieService.PublishedPlan> publication = planPublieService.publicationInForce();
            if (publication.isPresent()) {
                PlanPublieService.PublishedPlan published = publication.get();
                return new HoursReading(
                        Source.PUBLIE.param(),
                        published.publication().publieLe(),
                        true,
                        hoursService.compute(published.plan()));
            }
            if (source == Source.PUBLIE) {
                throw new BusinessError.Conflict("Aucun planning n'a encore été publié dans cette édition : les"
                        + " heures du plan publié n'existent pas. Lisez celles du plan enregistré, ou publiez"
                        + " d'abord.");
            }
            // The lookup above already answered: asking again would only cost a query.
            publicationAvailable = false;
        }
        DatedPlan persisted = readPersisted();
        return new HoursReading(
                Source.PERSISTE.param(),
                persisted.date(),
                publicationAvailable,
                hoursService.compute(persisted.plan()));
    }

    /**
     * The persisted plan and the date of its last solve or restore, read so
     * that one dates the other. They are two reads, and a solve landing
     * between them — it writes the seats and the date in one transaction —
     * would print the new date over the old seats, or the reverse. The date is
     * therefore read on both sides of the plan: when it did not move, nothing
     * landed in between; when it did, the plan is read once more, after the
     * landing. Once only: two landings within one read are not worth a loop,
     * and the plan is then dated by the last read of the date.
     */
    private DatedPlan readPersisted() {
        Instant before = resolvedAt();
        PlanningEvenement plan = persistenceService.loadPersistedPlanning();
        Instant after = resolvedAt();
        if (!Objects.equals(before, after)) {
            plan = persistenceService.loadPersistedPlanning();
            after = resolvedAt();
        }
        return new DatedPlan(after, plan);
    }

    private Instant resolvedAt() {
        PlanningPersistenceService.PlanningResolution resolution = persistenceService.loadResolution();
        return resolution == null ? null : resolution.resoluLe();
    }

    /** A plan and the date it was read with. */
    private record DatedPlan(Instant date, PlanningEvenement plan) {}

    /** The CSV of one reading: the sentence naming its plan, then the report as {@link PlanningHoursService#generateCsv}. */
    public String csv(HoursReading reading) {
        return "Source : " + provenance(reading) + "\n" + hoursService.generateCsv(reading.report());
    }

    /**
     * « plan publié le 12/07/2026 à 18:30 », « plan enregistré, résolu le … ».
     * The persisted plan is dated by its last solve or restore, the date the
     * PDF footers give it too: a seat moved by hand since is not dated anywhere,
     * so the sentence says what the date is rather than passing it off as the
     * plan's last change.
     */
    static String provenance(HoursReading reading) {
        boolean published = Source.PUBLIE.param().equals(reading.source());
        if (reading.planDate() == null) {
            // Only the persisted plan can lack a date: a publication is its date.
            return published ? "plan publié" : "plan enregistré, jamais résolu";
        }
        String date = DATE_PLAN.format(reading.planDate().atZone(ZoneId.systemDefault()));
        return published ? "plan publié le " + date : "plan enregistré, résolu le " + date;
    }

    /**
     * @param source                which plan was read: {@code persiste} or {@code publie}
     * @param planDate              when that plan was dated — the publication for
     *                              {@code publie}, the last solve or restore for
     *                              {@code persiste}; {@code null} when it never was
     * @param publicationAvailable  whether this edition has a publication in force,
     *                              so a screen can offer the other plan or not
     * @param report                the hours themselves
     */
    @Schema(requiredProperties = {"source", "publicationAvailable", "report"})
    public record HoursReading(
            @Schema(enumeration = {"persiste", "publie"}) String source,
            Instant planDate,
            boolean publicationAvailable,
            HeuresRapport report) {}
}
