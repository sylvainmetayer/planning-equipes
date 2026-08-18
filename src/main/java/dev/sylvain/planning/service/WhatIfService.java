package dev.sylvain.planning.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.PlanningFestival;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.FeasibilityAnalyzer.FeasibilityReport;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * "What if?" simulation (issue #73): answers questions like "three people just
 * cancelled, do I still have enough?" or "the outdoor stand closes tomorrow,
 * what does it change?" <b>without touching the referential</b>.
 *
 * <p>Mutations are applied server-side to in-memory copies of the reference
 * data. Nothing here writes: no repository call, no persistence, and the
 * objects built are thrown away with the request. Building the variant on the
 * server rather than in the browser is also what keeps it usable at real scale
 * — a full {@code PlanningFestival} of the 2026 festival does not fit in an
 * HTTP body.</p>
 */
@ApplicationScoped
public class WhatIfService {

    /**
     * Prefix of the ids given to the fictional animateurs a simulation adds, so
     * they are recognisable in a diagnostic and can never collide with a real
     * id (real ones come from the referential, which has no such prefix).
     */
    public static final String PREFIXE_ANIMATEUR_FICTIF = "WHATIF-";

    @Inject
    ReferenceDataService referenceDataService;

    @Inject
    FeasibilityAnalyzer feasibilityAnalyzer;

    @Inject
    PlanningService planningService;

    /**
     * The variant to evaluate.
     *
     * @param animateursAjoutes    how many fictional animateurs to add — the
     *                             "we recruit three more" question
     * @param animateursRetires    ids of animateurs to take out — the
     *                             "three people cancel" question
     * @param standsFermes         ids of stands to close for the whole festival
     * @param effectifsMin         stand id → required headcount to use instead
     *                             of the stand's own {@code effectifMin}
     */
    public record Mutations(
            int animateursAjoutes,
            List<String> animateursRetires,
            List<String> standsFermes,
            Map<String, Integer> effectifsMin) {

        public Mutations {
            animateursRetires = animateursRetires == null ? List.of() : List.copyOf(animateursRetires);
            standsFermes = standsFermes == null ? List.of() : List.copyOf(standsFermes);
            effectifsMin = effectifsMin == null ? Map.of() : Map.copyOf(effectifsMin);
            animateursAjoutes = Math.max(0, animateursAjoutes);
        }

        public boolean vide() {
            return animateursAjoutes == 0 && animateursRetires.isEmpty() && standsFermes.isEmpty()
                    && effectifsMin.isEmpty();
        }
    }

    /**
     * What the variant looks like, next to the untouched reference data, so the
     * UI can show the delta rather than a bare verdict.
     *
     * @param reference  feasibility of the referential as it stands today
     * @param simulation feasibility of the variant
     */
    public record ResultatWhatIf(
            int animateurs,
            int animateursReference,
            int standsOuverts,
            int standsOuvertsReference,
            int creneaux,
            FeasibilityReport reference,
            FeasibilityReport simulation) {
    }

    /** Instant answer: the capacity check, no solve involved. */
    public ResultatWhatIf simuler(Mutations mutations) {
        List<Animateur> animateursReference = referenceDataService.listAnimateurs();
        List<Stand> standsReference = referenceDataService.listStandsResolus();
        List<Creneau> creneaux = referenceDataService.listCreneauxGroupeActif();

        List<Animateur> animateurs = appliquerAuxAnimateurs(animateursReference, mutations);
        List<Stand> stands = appliquerAuxStands(standsReference, mutations);

        return new ResultatWhatIf(
                animateurs.size(),
                animateursReference.size(),
                stands.size(),
                standsReference.size(),
                creneaux.size(),
                feasibilityAnalyzer.analyser(animateursReference, standsReference, creneaux),
                feasibilityAnalyzer.analyser(animateurs, stands, creneaux));
    }

    /**
     * The variant as a solvable problem, for the explicitly-requested short
     * solve. Built exactly like a real solve's problem — same seats, same locks
     * — so its score is comparable to the reference one, but it is never
     * persisted: the caller analyses it and drops it.
     */
    public PlanningFestival construireProbleme(Mutations mutations) {
        PlanningFestival probleme = planningService.construireDepuisReferenceData(
                appliquerAuxAnimateurs(referenceDataService.listAnimateurs(), mutations),
                appliquerAuxStands(referenceDataService.listStandsResolus(), mutations),
                referenceDataService.listCreneauxGroupeActif());
        return probleme;
    }

    private List<Animateur> appliquerAuxAnimateurs(List<Animateur> reference, Mutations mutations) {
        Set<String> retires = new LinkedHashSet<>(mutations.animateursRetires());
        List<Animateur> animateurs = new ArrayList<>(reference.stream()
                .filter(animateur -> !retires.contains(animateur.getId()))
                .toList());
        for (int i = 1; i <= mutations.animateursAjoutes(); i++) {
            animateurs.add(animateurFictif(i));
        }
        return animateurs;
    }

    /**
     * A fictional recruit: adult, always available, and versatile (ninja), i.e.
     * eligible for any stand. That is deliberately the <b>optimistic</b>
     * hypothesis — "if I found someone who can hold anything" — and the answer
     * has to be read as such: a real recruit limited to two typologies helps
     * less than this one does.
     */
    private Animateur animateurFictif(int numero) {
        Animateur animateur = new Animateur(PREFIXE_ANIMATEUR_FICTIF + numero,
                "Animateur", "fictif " + numero,
                java.time.LocalDate.now().minusYears(30), false);
        animateur.setNinja(true);
        return animateur;
    }

    private List<Stand> appliquerAuxStands(List<Stand> reference, Mutations mutations) {
        Set<String> fermes = new LinkedHashSet<>(mutations.standsFermes());
        List<Stand> stands = new ArrayList<>();
        for (Stand stand : reference) {
            if (fermes.contains(stand.getId())) {
                continue;
            }
            Integer effectif = mutations.effectifsMin().get(stand.getId());
            if (effectif != null && effectif > 0) {
                // Mutated in place, and that is safe: `listStandsResolus()`
                // rebuilds its stands from the database on every call (with the
                // horaires expanded onto transient fields), so these objects
                // belong to this request alone and are never written back.
                stand.setEffectifMin(effectif);
                stand.setEffectifMax(Math.max(effectif, stand.getEffectifMax()));
            }
            stands.add(stand);
        }
        return stands;
    }
}
