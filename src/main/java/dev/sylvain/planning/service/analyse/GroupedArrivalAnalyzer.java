package dev.sylvain.planning.service.analyse;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.ContrainteAdHoc;
import dev.sylvain.planning.domain.ParametresQualite;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.TypeContrainteAdHoc;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Day by day, whether each {@code ARRIVEE_GROUPEE} group of a plan arrives and
 * leaves together — the read-out behind {@code GET /api/planning/arrivees-groupees},
 * the markers of the Rail, the « jours désalignés » line of the Problèmes screen
 * and the per-day indication of the espace.
 *
 * <p>Read with the arithmetic of the soft rule {@code arriveeGroupee}: a day
 * counts when at least one member works; it is aligned when every member works
 * and the first starts, like the last ends, are no further apart than the
 * tolerance. The start and the end are those of the <em>effective</em> windows
 * held that day, so a meal break or a legal break in the middle changes
 * nothing. A reading, not a score: past days stay listed.</p>
 *
 * <p>Ids only, like every read-out an MCP client may receive: a screen joins
 * the names.</p>
 */
@ApplicationScoped
public class GroupedArrivalAnalyzer {

    /** One member's day: first start and last end of what they hold. */
    public record MemberHoursView(String animateurId, LocalTime start, LocalTime end) {}

    /**
     * One day of one group.
     *
     * @param aligned                every member works, within the tolerance
     *                               on both ends
     * @param working                members holding at least one seat that day
     * @param absent                 members holding none while another does —
     *                               what the rule charges a flat penalty for
     * @param arrivalSpreadMinutes   spread of the first starts among those who
     *                               work
     * @param departureSpreadMinutes spread of the last ends
     */
    @Schema(requiredProperties = {"aligned", "arrivalSpreadMinutes", "departureSpreadMinutes"})
    public record GroupDayView(
            LocalDate date,
            boolean aligned,
            List<String> working,
            List<String> absent,
            int arrivalSpreadMinutes,
            int departureSpreadMinutes,
            List<MemberHoursView> hours) {}

    /** One group: the exception that declares it, its members, and its days. */
    @Schema(requiredProperties = {"misalignedDays"})
    public record GroupView(
            String contrainteId, List<String> animateurIds, int misalignedDays, List<GroupDayView> days) {}

    @Schema(requiredProperties = {"toleranceMinutes", "groups"})
    public record GroupedArrivalReport(int toleranceMinutes, List<GroupView> groups) {}

    public GroupedArrivalReport analyze(
            PlanningEvenement planning, List<ContrainteAdHoc> contraintes, ParametresQualite qualite) {
        int tolerance = qualite.toleranceArriveeGroupeeMinutes();
        List<PosteAffectation> postes =
                planning == null || planning.getPostes() == null ? List.of() : planning.getPostes();
        List<GroupView> groupes = groups(contraintes).stream()
                .map(contrainte -> read(contrainte, postes, tolerance))
                .toList();
        return new GroupedArrivalReport(tolerance, groupes);
    }

    /** The well-formed grouped arrivals among {@code contraintes}: two members at least. */
    public static List<ContrainteAdHoc> groups(Collection<ContrainteAdHoc> contraintes) {
        return contraintes == null
                ? List.of()
                : contraintes.stream()
                        .filter(contrainte -> contrainte.getType() == TypeContrainteAdHoc.ARRIVEE_GROUPEE
                                && memberIds(contrainte).size() >= 2)
                        .toList();
    }

    /** Distinct member ids, in the order the exception lists them. */
    public static List<String> memberIds(ContrainteAdHoc contrainte) {
        return contrainte.getAnimateursConcernes().stream()
                .filter(Objects::nonNull)
                .map(Animateur::getId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

    /** Every day of one group on which at least one member works, chronologically. */
    public static GroupView read(ContrainteAdHoc contrainte, List<PosteAffectation> postes, int tolerance) {
        List<String> membres = memberIds(contrainte);
        // date → member → [first start, last end]
        Map<LocalDate, Map<String, LocalDateTime[]>> jours = new TreeMap<>();
        for (PosteAffectation poste : postes) {
            if (!readable(poste) || !membres.contains(poste.getAnimateur().getId())) {
                continue;
            }
            LocalDateTime debut = LocalDateTime.of(poste.getCreneau().getDate(), poste.heureDebutEffectif());
            LocalDateTime fin = debut.plusMinutes(poste.getDureeEffectiveMinutes());
            LocalDateTime[] bornes = jours.computeIfAbsent(poste.getCreneau().getDate(), k -> new TreeMap<>())
                    .computeIfAbsent(poste.getAnimateur().getId(), k -> new LocalDateTime[] {debut, fin});
            if (debut.isBefore(bornes[0])) {
                bornes[0] = debut;
            }
            if (fin.isAfter(bornes[1])) {
                bornes[1] = fin;
            }
        }
        List<GroupDayView> vues = new ArrayList<>();
        for (Map.Entry<LocalDate, Map<String, LocalDateTime[]>> jour : jours.entrySet()) {
            vues.add(readDay(jour.getKey(), membres, jour.getValue(), tolerance));
        }
        int desalignes = (int) vues.stream().filter(vue -> !vue.aligned()).count();
        return new GroupView(contrainte.getId(), membres, desalignes, List.copyOf(vues));
    }

    private static GroupDayView readDay(
            LocalDate date, List<String> membres, Map<String, LocalDateTime[]> bornes, int tolerance) {
        List<String> travaillent = membres.stream().filter(bornes::containsKey).toList();
        List<String> absents =
                membres.stream().filter(membre -> !bornes.containsKey(membre)).toList();
        TreeSet<LocalDateTime> debuts = new TreeSet<>();
        TreeSet<LocalDateTime> fins = new TreeSet<>();
        List<MemberHoursView> horaires = new ArrayList<>();
        for (String membre : travaillent) {
            LocalDateTime[] journee = bornes.get(membre);
            debuts.add(journee[0]);
            fins.add(journee[1]);
            horaires.add(new MemberHoursView(membre, journee[0].toLocalTime(), journee[1].toLocalTime()));
        }
        int ecartArrivee = (int) Duration.between(debuts.first(), debuts.last()).toMinutes();
        int ecartDepart = (int) Duration.between(fins.first(), fins.last()).toMinutes();
        boolean aligne = absents.isEmpty() && ecartArrivee <= tolerance && ecartDepart <= tolerance;
        return new GroupDayView(date, aligne, travaillent, absents, ecartArrivee, ecartDepart, horaires);
    }

    private static boolean readable(PosteAffectation poste) {
        return poste.getAnimateur() != null
                && poste.getCreneau() != null
                && poste.getCreneau().getDate() != null
                && poste.heureDebutEffectif() != null
                && poste.heureFinEffectif() != null;
    }
}
