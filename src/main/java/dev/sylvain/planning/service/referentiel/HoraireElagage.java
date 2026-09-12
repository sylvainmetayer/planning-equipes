package dev.sylvain.planning.service.referentiel;

import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.Creneau.SegmentOuvert;
import dev.sylvain.planning.domain.HoraireStand;
import dev.sylvain.planning.domain.IndisponibiliteStand;
import dev.sylvain.planning.domain.ModeHoraire;
import dev.sylvain.planning.domain.Stand;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Drops the closures a stand no longer needs, keeping its openings to the
 * minute and to the seat.
 *
 * <p>Saying "open on these twelve dates, and nothing else" used to take two
 * rules: the opening, and a closure whose only job was to shut the days the
 * opening did not name — written as a closure running from an hour early
 * enough to cover every créneau, {@code 09:00} until closing on a real
 * edition, where sixty-five stands carried one each. Now that a stand
 * declaring openings is closed wherever it declares none
 * ({@link HoraireStandResolver#declaresOpenings}), that second rule says
 * nothing the first does not.</p>
 *
 * <p>Two deliberate limits, and both are about not deciding for the operator:</p>
 * <ul>
 * <li><b>Only closures go.</b> An opening is never pruned, however little it
 * does against today's créneaux: "open 08:00-09:00" on a grid that starts at
 * 10:00 is a statement waiting for a créneau, not dead weight.</li>
 * <li><b>Only on a stand that declares openings.</b> A stand whose rules only
 * close is describing exceptions to being open, and every one of them is
 * load-bearing the moment a créneau moves into it.</li>
 * </ul>
 *
 * <p>Within that, nothing is judged by shape: each closure is removed in turn
 * and the stand re-resolved against its own créneaux, the removal kept only
 * when <b>two</b> things come back identical — every open segment of every
 * slot, headcount included, and every opening window the resolution produces.
 * Both, because either alone lets something through: a closure can be the only
 * thing shutting a day an opening rule covers at an hour no créneau touches,
 * and dropping it changes no segment while declaring an opening nobody wrote;
 * and a closure that carves a hole in a day can be the difference between a
 * day half open and a day shut, with no opening window on either side.</p>
 *
 * <p>Pure and static, like {@link HoraireCompaction} next to it.</p>
 */
public final class HoraireElagage {

    private HoraireElagage() {}

    /** What pruning would do, or did, to one stand. */
    public record LigneElagage(
            String standId, int reglesAvant, int reglesApres, int fermeturesRetirees, List<String> retirees) {

        public boolean elague() {
            return reglesApres < reglesAvant || fermeturesRetirees > 0;
        }
    }

    /**
     * Prunes every stand in place and says what went. A stand whose closures are
     * all load-bearing comes back untouched, and its line reports no change.
     */
    public static List<LigneElagage> elaguer(List<Stand> stands, List<Creneau> creneaux) {
        List<LigneElagage> lignes = new ArrayList<>();
        for (Stand stand : stands) {
            lignes.add(elaguer(stand, creneaux));
        }
        return lignes;
    }

    /**
     * Prunes one stand in place. Closures are tried from the last to the first,
     * so that between two saying the same thing the one the operator typed
     * first is the one that survives.
     */
    public static LigneElagage elaguer(Stand stand, List<Creneau> creneaux) {
        List<HoraireStand> regles = new ArrayList<>(stand.getHoraires());
        List<IndisponibiliteStand> fermetures = new ArrayList<>(stand.getIndisponibilites());
        int reglesAvant = regles.size();
        int fermeturesAvant = fermetures.size();
        if (creneaux.isEmpty() || !HoraireStandResolver.declaresOpenings(regles)) {
            return new LigneElagage(stand.getId(), reglesAvant, reglesAvant, 0, List.of());
        }
        List<String> retirees = new ArrayList<>();
        Resolution reference = resolution(stand, regles, fermetures, creneaux);
        for (int i = regles.size() - 1; i >= 0; i--) {
            if (regles.get(i).getMode() != ModeHoraire.FERMETURE) {
                continue;
            }
            List<HoraireStand> essai = new ArrayList<>(regles);
            HoraireStand candidate = essai.remove(i);
            if (resolution(stand, essai, fermetures, creneaux).equals(reference)) {
                regles = essai;
                retirees.add(candidate.toString());
            }
        }
        // Then the "this day is shut" markers: a whole day closed by hand on a
        // stand whose rules already leave that day out states it twice. This is
        // the grid's own marker for an empty column, so an edition entered there
        // carries one per stand per unused day.
        for (int i = fermetures.size() - 1; i >= 0; i--) {
            IndisponibiliteStand fermeture = fermetures.get(i);
            if (!journeeEntiere(fermeture)) {
                continue;
            }
            List<IndisponibiliteStand> essai = new ArrayList<>(fermetures);
            essai.remove(i);
            if (resolution(stand, regles, essai, creneaux).equals(reference)) {
                fermetures = essai;
                retirees.add("fermeture du " + fermeture.getDate());
            }
        }
        if (regles.size() < reglesAvant || fermetures.size() < fermeturesAvant) {
            stand.setHoraires(regles);
            stand.setIndisponibilites(fermetures);
            stand.setFenetresEffectives(null, null);
        }
        return new LigneElagage(
                stand.getId(), reglesAvant, regles.size(), fermeturesAvant - fermetures.size(), List.copyOf(retirees));
    }

    /** "Shut, all day": the marker an empty grid column leaves behind. */
    private static boolean journeeEntiere(IndisponibiliteStand fermeture) {
        return fermeture.getHeureFin() == null && LocalTime.MIDNIGHT.equals(fermeture.getHeureDebut());
    }

    /** What a pruning must leave untouched: the openings declared, and the openings served. */
    private record Resolution(List<String> ouvertures, Map<String, List<SegmentOuvert>> segments) {}

    /**
     * The stand as the given rules and closures resolve it. Computed on a copy
     * so the stand handed in never carries a trial resolution.
     */
    private static Resolution resolution(
            Stand stand, List<HoraireStand> regles, List<IndisponibiliteStand> fermetures, List<Creneau> creneaux) {
        Stand essai = copie(stand, regles, fermetures);
        HoraireStandResolver.apply(List.of(essai), creneaux);
        List<String> ouvertures = essai.getOuverturesEffectives().stream()
                .map(fenetre -> fenetre.getDate() + "|" + fenetre.getHeureDebut() + "|" + fenetre.getHeureFin() + "|"
                        + fenetre.getEffectif())
                .sorted()
                .toList();
        Map<String, List<SegmentOuvert>> segments = new LinkedHashMap<>();
        for (Creneau creneau : creneaux) {
            List<SegmentOuvert> ouverts = creneau.segmentsOuverts(essai);
            if (!ouverts.isEmpty()) {
                segments.put(creneau.getDate() + "|" + creneau.getHeureDebut() + "|" + creneau.getHeureFin(), ouverts);
            }
        }
        return new Resolution(ouvertures, segments);
    }

    /**
     * A stand carrying the trial rules. The headcount bounds come along: a
     * window that names no effectif falls back on {@link Stand#getEffectifMin()},
     * so a copy without them would compare seats that do not exist.
     */
    private static Stand copie(Stand modele, List<HoraireStand> regles, List<IndisponibiliteStand> fermetures) {
        Stand copie = new Stand();
        copie.setId(modele.getId());
        copie.setNom(modele.getNom());
        copie.setEffectifMin(modele.getEffectifMin());
        copie.setEffectifMax(modele.getEffectifMax());
        copie.setHoraires(new ArrayList<>(regles));
        copie.setIndisponibilites(new ArrayList<>(fermetures));
        copie.setOuvertures(new ArrayList<>(modele.getOuvertures()));
        return copie;
    }
}
