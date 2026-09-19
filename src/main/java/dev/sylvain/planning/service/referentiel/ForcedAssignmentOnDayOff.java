package dev.sylvain.planning.service.referentiel;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.ContrainteAdHoc;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.PastHorizon;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.domain.TypeContrainteAdHoc;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * A forced assignment that falls only on days every animateur it names has
 * declared off: it cannot be kept without placing somebody on a day off.
 *
 * <p>{@link ContrainteAdHocContradictions} compares exceptions with each other;
 * this compares one with the days people declared, which is why it cannot be a
 * refusal. The day off usually arrives after the exception, through the
 * animateur's own declaration — refusing the declaration is not an option, and
 * refusing the exception would only hold until then. So the write of the
 * exception is warned, and the pre-solve analysis reports it as a blocking
 * cause whenever it stands, whichever of the two came first.</p>
 *
 * <p>Pure and static, read the same way by both: the scope is the exception's
 * timeslot when it names one, otherwise every date of the grid where its stand
 * (or any stand) opens. A stand whose recurring openings were not resolved
 * reads as open everywhere, which widens the scope and can only make a
 * conflict rarer — never invent one.</p>
 */
public final class ForcedAssignmentOnDayOff {

    /** How many dates a message spells out before it says "and n others". */
    private static final int DATES_CITEES = 5;

    private ForcedAssignmentOnDayOff() {}

    /** @param dates the dates of the scope, all of them days off for every animateur named */
    public record Conflit(ContrainteAdHoc contrainte, List<LocalDate> dates) {

        public String message() {
            String cites =
                    dates.stream().limit(DATES_CITEES).map(LocalDate::toString).collect(Collectors.joining(", "));
            String reste = dates.size() > DATES_CITEES ? " et " + (dates.size() - DATES_CITEES) + " autre(s)" : "";
            return "L'affectation forcée " + contrainte.getId() + " ne peut pas être tenue : "
                    + (contrainte.getAnimateursConcernes().size() > 1
                            ? "chacun des animateurs qu'elle nomme"
                            : "l'animateur qu'elle nomme")
                    + " s'est déclaré indisponible le " + cites + reste + ".";
        }
    }

    public static List<Conflit> detectAll(
            List<ContrainteAdHoc> contraintes,
            List<Animateur> animateurs,
            List<Stand> stands,
            List<Creneau> creneaux,
            PastHorizon horizon) {
        if (contraintes == null || contraintes.isEmpty()) {
            return List.of();
        }
        Map<String, Animateur> animateursParId = index(animateurs, Animateur::getId);
        Map<String, Stand> standsParId = index(stands, Stand::getId);
        List<Conflit> conflits = new ArrayList<>();
        for (ContrainteAdHoc contrainte : contraintes) {
            detect(contrainte, animateursParId, standsParId, creneaux, horizon).ifPresent(conflits::add);
        }
        return List.copyOf(conflits);
    }

    public static Optional<Conflit> detect(
            ContrainteAdHoc contrainte,
            Map<String, Animateur> animateursParId,
            Map<String, Stand> standsParId,
            List<Creneau> creneaux,
            PastHorizon horizon) {
        if (contrainte == null
                || contrainte.getType() != TypeContrainteAdHoc.AFFECTATION_FORCEE
                || contrainte.getAnimateursConcernes() == null
                || contrainte.getAnimateursConcernes().isEmpty()) {
            return Optional.empty();
        }
        List<Animateur> nommes = new ArrayList<>();
        for (Animateur reference : contrainte.getAnimateursConcernes()) {
            Animateur animateur = reference == null ? null : animateursParId.get(reference.getId());
            if (animateur == null) {
                // Somebody the referential does not know: nothing to compare with.
                return Optional.empty();
            }
            nommes.add(animateur);
        }
        TreeSet<LocalDate> perimetre = dates(contrainte, standsParId, creneaux == null ? List.of() : creneaux);
        // A scope entirely behind us is history, not a hole to fill:
        // `affectationForcee` does not charge it either (ADR 0044).
        if (perimetre.isEmpty() || perimetre.stream().noneMatch(jour -> ForcedAssignmentPast.aVenir(jour, horizon))) {
            return Optional.empty();
        }
        boolean intenable =
                nommes.stream().allMatch(animateur -> perimetre.stream().allMatch(animateur::isIndisponibleOn));
        return intenable ? Optional.of(new Conflit(contrainte, List.copyOf(perimetre))) : Optional.empty();
    }

    private static TreeSet<LocalDate> dates(
            ContrainteAdHoc contrainte, Map<String, Stand> standsParId, List<Creneau> creneaux) {
        TreeSet<LocalDate> dates = new TreeSet<>();
        if (contrainte.getCreneau() != null) {
            Long id = contrainte.getCreneau().getId();
            creneaux.stream()
                    .filter(creneau -> id != null && id.equals(creneau.getId()))
                    .map(Creneau::getDate)
                    .filter(Objects::nonNull)
                    .findFirst()
                    .or(() -> Optional.ofNullable(contrainte.getCreneau().getDate()))
                    .ifPresent(dates::add);
            return dates;
        }
        Stand stand = contrainte.getStand() == null
                ? null
                : standsParId.get(contrainte.getStand().getId());
        for (Creneau creneau : creneaux) {
            if (creneau.getDate() != null && (stand == null || creneau.isStandOpen(stand))) {
                dates.add(creneau.getDate());
            }
        }
        return dates;
    }

    private static <T> Map<String, T> index(List<T> elements, Function<T, String> id) {
        return elements == null
                ? Map.of()
                : elements.stream()
                        .filter(element -> id.apply(element) != null)
                        .collect(Collectors.toMap(id, Function.identity(), (premier, doublon) -> premier));
    }
}
