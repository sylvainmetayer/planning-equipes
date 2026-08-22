package dev.sylvain.planning.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.FenetreHoraire;
import dev.sylvain.planning.domain.HoraireStand;
import dev.sylvain.planning.domain.IndisponibiliteStand;
import dev.sylvain.planning.domain.ModeHoraire;
import dev.sylvain.planning.domain.OuvertureStand;
import dev.sylvain.planning.domain.Stand;

/**
 * Expands a stand's recurring {@link HoraireStand} rules into the dated windows
 * the rest of the application already knows how to read, day by day, and records
 * the result on the stand ({@link Stand#setFenetresEffectives}).
 *
 * <p>This is the single place the layering described in {@link HoraireStand} is
 * implemented: per calendar day, a dated exception wins outright, otherwise the
 * covering rules of highest specificity do, otherwise the day is left alone
 * (open by default). Because the outcome is always a single
 * {@link ModeHoraire} per day, the three-state invariant
 * {@link Creneau#segmentsOuvertsMinutes(Stand)} relies on holds by construction
 * — which is why nothing downstream of here (constraints, poste generation,
 * exports) needed to change when rules were introduced.</p>
 *
 * <p>Pure and static, like {@code VacationGeneratorService}: no CDI, no
 * database, unit-testable on plain objects.</p>
 */
public final class HoraireStandResolver {

    private HoraireStandResolver() {
    }

    /**
     * Resolves every stand of {@code stands} against the days {@code creneaux}
     * spans. Stands without a single rule are left strictly untouched, so a
     * dataset that never uses rules keeps the exact object graph — and the exact
     * behaviour — it had before.
     */
    public static void apply(Collection<Stand> stands, Collection<Creneau> creneaux) {
        Set<LocalDate> dates = datesConcernees(creneaux);
        if (dates.isEmpty()) {
            return;
        }
        for (Stand stand : stands) {
            apply(stand, dates);
        }
    }

    /**
     * Days a resolution has to cover: every date carrying a créneau, plus the
     * day after a créneau that crosses midnight — the only case
     * {@link Creneau#segmentsOuvertsMinutes(Stand)} reads a window dated later
     * than the slot's own date. Expanding beyond that would be pointless at
     * best (an unreadable window) and misleading at worst.
     */
    static Set<LocalDate> datesConcernees(Collection<Creneau> creneaux) {
        Set<LocalDate> dates = new TreeSet<>();
        for (Creneau creneau : creneaux) {
            LocalDate date = creneau.getDate();
            if (date == null) {
                continue;
            }
            dates.add(date);
            if (creneau.getHeureDebut() != null && creneau.getHeureFin() != null
                    && !creneau.getHeureFin().isAfter(creneau.getHeureDebut())) {
                dates.add(date.plusDays(1));
            }
        }
        return dates;
    }

    /**
     * Resolves one stand. A no-op without rules; otherwise both effective lists
     * are rebuilt from scratch, so calling this twice on the same stand yields
     * the same result (the dated exceptions it reads are never modified).
     */
    public static void apply(Stand stand, Collection<LocalDate> dates) {
        if (stand == null || stand.getHoraires().isEmpty()) {
            return;
        }
        Set<LocalDate> joursAvecException = new LinkedHashSet<>();
        stand.getIndisponibilites().stream().map(IndisponibiliteStand::getDate).filter(Objects::nonNull)
                .forEach(joursAvecException::add);
        stand.getOuvertures().stream().map(OuvertureStand::getDate).filter(Objects::nonNull)
                .forEach(joursAvecException::add);

        List<IndisponibiliteStand> fermetures = new ArrayList<>(stand.getIndisponibilites());
        List<OuvertureStand> ouvertures = new ArrayList<>(stand.getOuvertures());
        for (LocalDate date : dates) {
            if (joursAvecException.contains(date)) {
                // Layer 1: the day is stated by hand, rules stay out of it.
                continue;
            }
            JourResolu resolu = resolveDay(stand.getHoraires(), date);
            if (resolu == null) {
                continue;
            }
            for (FenetreHoraire fenetre : resolu.fenetres()) {
                if (resolu.mode() == ModeHoraire.OUVERTURE) {
                    ouvertures.add(new OuvertureStand(null, date, fenetre.getHeureDebut(), fenetre.getHeureFin(),
                            resolu.motif()));
                } else {
                    fermetures.add(new IndisponibiliteStand(null, date, fenetre.getHeureDebut(), fenetre.getHeureFin(),
                            resolu.motif()));
                }
            }
        }
        stand.setFenetresEffectives(fermetures, ouvertures);
    }

    /** Which of the three layers decided a given day — see {@link HoraireStand}. */
    public enum SourceHoraire {
        /** Nothing states anything about that day: open all day. */
        DEFAUT,
        /** A recurring rule covers it. */
        REGLE,
        /** A dated window names it, and therefore overrides the rules. */
        EXCEPTION
    }

    /**
     * Which layer governs {@code date} for {@code stand}. Reads the <b>dated</b>
     * lists, not the effective ones, so the answer stays the same whether or not
     * a resolution has already run — otherwise every day would look like an
     * exception once expanded.
     */
    public static SourceHoraire sourceOfDay(Stand stand, LocalDate date) {
        if (stand == null || date == null) {
            return SourceHoraire.DEFAUT;
        }
        boolean datee = stand.getIndisponibilites().stream().anyMatch(f -> date.equals(f.getDate()))
                || stand.getOuvertures().stream().anyMatch(o -> date.equals(o.getDate()));
        if (datee) {
            return SourceHoraire.EXCEPTION;
        }
        return resolveDay(stand.getHoraires(), date) != null ? SourceHoraire.REGLE : SourceHoraire.DEFAUT;
    }

    /** What the rules say about one day: one mode, and the windows to apply. */
    record JourResolu(ModeHoraire mode, List<FenetreHoraire> fenetres, String motif) {
    }

    /**
     * The rules' verdict for {@code date}, or {@code null} when none covers it
     * (the day is then left open by default). Only the covering rules of highest
     * {@link HoraireStand#specificite()} are kept, and the union of their windows
     * is returned.
     *
     * <p>Should two rules of that same top specificity disagree on the mode,
     * {@link ModeHoraire#OUVERTURE} wins and the closures are dropped. That
     * combination is rejected at write time
     * ({@code ReferenceDataService#validateHoraires}), so this is a defensive
     * tie-break for data arriving another way — a hand-written scenario file,
     * say. Opening wins because it is the more restrictive reading of the two:
     * closed-by-default outside the listed windows never staffs a stand somebody
     * declared shut, whereas the other choice would.</p>
     */
    static JourResolu resolveDay(List<HoraireStand> horaires, LocalDate date) {
        List<HoraireStand> couvrantes = horaires.stream()
                .filter(horaire -> horaire.couvre(date))
                .filter(horaire -> !horaire.validFenetres().isEmpty())
                .toList();
        if (couvrantes.isEmpty()) {
            return null;
        }
        int specificiteMax = couvrantes.stream().mapToInt(HoraireStand::specificite).max().orElseThrow();
        List<HoraireStand> gagnantes = couvrantes.stream()
                .filter(horaire -> horaire.specificite() == specificiteMax)
                .toList();
        ModeHoraire mode = gagnantes.stream().anyMatch(horaire -> horaire.getMode() == ModeHoraire.OUVERTURE)
                ? ModeHoraire.OUVERTURE
                : ModeHoraire.FERMETURE;
        List<FenetreHoraire> fenetres = gagnantes.stream()
                .filter(horaire -> horaire.getMode() == mode)
                .flatMap(horaire -> horaire.validFenetres().stream())
                .distinct()
                .sorted(Comparator.comparing(FenetreHoraire::getHeureDebut))
                .toList();
        String motif = gagnantes.stream()
                .filter(horaire -> horaire.getMode() == mode)
                .map(HoraireStand::getMotif)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
        return new JourResolu(mode, fenetres, motif);
    }
}
