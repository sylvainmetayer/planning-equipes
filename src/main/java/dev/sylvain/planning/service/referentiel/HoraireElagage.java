package dev.sylvain.planning.service.referentiel;

import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.Creneau.SegmentOuvert;
import dev.sylvain.planning.domain.HoraireStand;
import dev.sylvain.planning.domain.IndisponibiliteStand;
import dev.sylvain.planning.domain.OuvertureStand;
import dev.sylvain.planning.domain.Stand;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Drops the rules a stand no longer needs, keeping its openings to the minute
 * and to the seat.
 *
 * <p>Saying "open on these twelve dates, and nothing else" used to take two
 * rules: the opening, and a closure whose only job was to shut the days the
 * opening did not name. Now that a stand declaring openings is closed wherever
 * it declares none ({@code HoraireStandResolver#declareDesOuvertures}), that
 * second rule says nothing the first does not — and it is the shape sixty-five
 * stands of a real edition carried, one each.</p>
 *
 * <p>Nothing is judged by shape. Each rule is removed in turn and the stand is
 * re-resolved against its own créneaux; the removal is kept only when every
 * open segment of every slot comes back identical, headcount included. What
 * that catches is wider than the closure above — a rule another rule already
 * shadows, a rule on dates the event no longer has — and it can never be wrong
 * by construction: a pruning that changed one minute is not applied.</p>
 *
 * <p>Pure and static, like {@link HoraireCompaction} next to it: no CDI, no
 * database, and the stands it is handed are the only state it touches.</p>
 */
public final class HoraireElagage {

    private HoraireElagage() {}

    /** What pruning would do, or did, to one stand. */
    public record LigneElagage(String standId, int reglesAvant, int reglesApres, List<String> reglesRetirees) {

        public boolean elague() {
            return reglesApres < reglesAvant;
        }
    }

    /**
     * Prunes every stand in place and says what went. A stand whose rules are
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
     * Prunes one stand in place. Rules are tried from the last to the first, so
     * that between two rules saying the same thing the one the operator typed
     * first is the one that survives.
     */
    public static LigneElagage elaguer(Stand stand, List<Creneau> creneaux) {
        List<HoraireStand> regles = new ArrayList<>(stand.getHoraires());
        int avant = regles.size();
        List<String> retirees = new ArrayList<>();
        if (avant == 0 || creneaux.isEmpty()) {
            return new LigneElagage(stand.getId(), avant, avant, List.of());
        }
        Map<String, List<SegmentOuvert>> reference = ouvertures(stand, regles, creneaux);
        for (int i = regles.size() - 1; i >= 0; i--) {
            List<HoraireStand> essai = new ArrayList<>(regles);
            HoraireStand candidate = essai.remove(i);
            if (ouvertures(stand, essai, creneaux).equals(reference)) {
                regles = essai;
                retirees.add(candidate.toString());
            }
        }
        if (regles.size() < avant) {
            stand.setHoraires(regles);
            stand.setFenetresEffectives(null, null);
        }
        return new LigneElagage(stand.getId(), avant, regles.size(), List.copyOf(retirees));
    }

    /**
     * The stand's open segments, slot by slot, as the given rules resolve them:
     * the thing a pruning must leave untouched. Computed on a copy so the stand
     * handed in never carries a trial resolution.
     */
    private static Map<String, List<SegmentOuvert>> ouvertures(
            Stand stand, List<HoraireStand> regles, List<Creneau> creneaux) {
        Stand essai = copie(stand, regles);
        HoraireStandResolver.apply(List.of(essai), creneaux);
        Map<String, List<SegmentOuvert>> ouvert = new LinkedHashMap<>();
        for (Creneau creneau : creneaux) {
            List<SegmentOuvert> segments = creneau.segmentsOuverts(essai);
            if (!segments.isEmpty()) {
                ouvert.put(creneau.getDate() + "|" + creneau.getHeureDebut() + "|" + creneau.getHeureFin(), segments);
            }
        }
        return ouvert;
    }

    /**
     * A stand carrying the trial rules. The headcount bounds come along: a
     * window that names no effectif falls back on {@link Stand#getEffectifMin()},
     * so a copy without them would compare seats that do not exist.
     */
    private static Stand copie(Stand modele, List<HoraireStand> regles) {
        Stand copie = new Stand();
        copie.setId(modele.getId());
        copie.setNom(modele.getNom());
        copie.setEffectifMin(modele.getEffectifMin());
        copie.setEffectifMax(modele.getEffectifMax());
        copie.setHoraires(new ArrayList<>(regles));
        copie.setIndisponibilites(new ArrayList<IndisponibiliteStand>(modele.getIndisponibilites()));
        copie.setOuvertures(new ArrayList<OuvertureStand>(modele.getOuvertures()));
        return copie;
    }
}
