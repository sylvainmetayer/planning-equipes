package dev.sylvain.planning.service.analyse;

import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.EffectiveWork;
import dev.sylvain.planning.domain.ParametresLegaux;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.analyse.PlanningHoursService.HeuresAnimateur;
import dev.sylvain.planning.service.analyse.RapportConformite.Breach;
import dev.sylvain.planning.service.analyse.RapportConformite.Personne;
import dev.sylvain.planning.service.analyse.RapportConformite.Plafonds;
import dev.sylvain.planning.service.analyse.RapportConformite.Regle;
import dev.sylvain.planning.service.analyse.RapportConformite.Synthese;
import dev.sylvain.planning.service.diagnostic.ConstraintContribution;
import dev.sylvain.planning.service.diagnostic.ConstraintDiagnosticService;
import dev.sylvain.planning.service.publication.PlanPublieService;
import dev.sylvain.planning.service.solve.PlanningService;
import dev.sylvain.planning.solver.ConstraintCatalog;
import dev.sylvain.planning.solver.ConstraintCatalog.ConstraintDefinition;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Writes the {@link RapportConformite} of the edition the call works in.
 *
 * <p><b>Nothing here is a rule.</b> The weekly hours come from
 * {@link PlanningHoursService}, the ceilings are read from the plan's
 * {@code ParametresLegaux} — the values the legal rules read — and the breaches
 * are the matches {@link ConstraintDiagnosticService} finds for the rules of
 * the legal categories of {@link ConstraintCatalog}. The plan is scored here
 * <b>without the edition's toggles</b>: a report for an inspector counts what
 * the law forbids, whether or not the organiser switched the rule off for the
 * solver.</p>
 */
@ApplicationScoped
public class RapportConformiteService {

    /** More than this many lines of one rule per person would be a wall of text: the count stays whole. */
    static final int MAX_ECARTS_PAR_REGLE_ET_PERSONNE = 5;

    private static final double EPSILON = 1e-9;

    private final PlanPublieService planPublieService;
    private final PlanningHoursService hoursService;
    private final ConstraintDiagnosticService diagnostic;

    @Inject
    public RapportConformiteService(
            PlanPublieService planPublieService, PlanningHoursService hoursService, PlanningService planningService) {
        this(planPublieService, hoursService, (ConstraintDiagnosticService) planningService::analyze);
    }

    RapportConformiteService(
            PlanPublieService planPublieService,
            PlanningHoursService hoursService,
            ConstraintDiagnosticService diagnostic) {
        this.planPublieService = planPublieService;
        this.hoursService = hoursService;
        this.diagnostic = diagnostic;
    }

    /**
     * The report of the published plan of the current edition.
     *
     * @throws BusinessError.Conflict when nothing was ever published: a draft is not handed to an inspector
     */
    public RapportConformite build(String editionId, String editionNom) {
        PlanPublieService.PublishedPlan publiee = planPublieService
                .publicationInForce()
                .orElseThrow(() -> new BusinessError.Conflict("Aucun planning n'a encore été publié dans cette"
                        + " édition : le rapport de conformité porte sur le plan publié, jamais sur un brouillon."));
        return build(editionId, editionNom, publiee.publication().publieLe(), publiee.plan(), Instant.now());
    }

    /** The report of {@code plan}, a pure function of it and of the clock handed in. */
    RapportConformite build(
            String editionId, String editionNom, Instant publieLe, PlanningEvenement plan, Instant genereLe) {
        ParametresLegaux parametres = plan.parametresLegaux();
        PlanningHoursService.HeuresRapport heures = hoursService.compute(plan);
        Map<String, List<Breach>> ecartsParPersonne = new LinkedHashMap<>();
        List<Regle> regles = rules(plan, ecartsParPersonne);
        double majeur = parametres.getDureeHebdomadaireMaxMinutes() / 60.0;
        double mineur = parametres.getDureeHebdomadaireMaxMineurMinutes() / 60.0;

        Map<String, List<PosteAffectation>> postesParPersonne = new LinkedHashMap<>();
        for (PosteAffectation poste : plan.getPostes()) {
            if (poste.getAnimateur() != null && poste.getCreneau() != null) {
                postesParPersonne
                        .computeIfAbsent(poste.getAnimateur().getId(), id -> new ArrayList<>())
                        .add(poste);
            }
        }
        List<Personne> personnes = new ArrayList<>();
        for (HeuresAnimateur ligne : heures.animateurs()) {
            List<PosteAffectation> postes = postesParPersonne.get(ligne.animateurId());
            if (postes != null) {
                personnes.add(person(
                        ligne,
                        postes,
                        parametres,
                        majeur,
                        mineur,
                        ecartsParPersonne.getOrDefault(ligne.animateurId(), List.of()),
                        heures.semaines()));
            }
        }
        personnes.sort(Comparator.comparing(Personne::nom, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(Personne::animateurId));
        return new RapportConformite(
                editionId,
                editionNom,
                publieLe,
                genereLe,
                new Plafonds(
                        majeur,
                        mineur,
                        parametres.getDureeVacationMaxMinutes() / 60.0,
                        parametres.getReposQuotidienMinimalMinutes() / 60.0,
                        differencesWithDefaults(parametres)),
                synthese(personnes, regles),
                heures.semaines(),
                regles,
                personnes);
    }

    private static Personne person(
            HeuresAnimateur ligne,
            List<PosteAffectation> postes,
            ParametresLegaux parametres,
            double plafondMajeur,
            double plafondMineur,
            List<Breach> ecarts,
            List<String> semaines) {
        // The two weekly rules split a week on the day the person comes of age: the minor days against
        // the minors' ceiling, the adult days against the adults' — never the whole week against one.
        Map<LocalDate, List<PosteAffectation>> parJour = new TreeMap<>();
        for (PosteAffectation poste : postes) {
            LocalDate date = poste.getCreneau().getDate();
            if (date != null) {
                parJour.computeIfAbsent(date, d -> new ArrayList<>()).add(poste);
            }
        }
        Map<String, Double> heuresMineures = new HashMap<>();
        boolean mineur = false;
        for (Map.Entry<LocalDate, List<PosteAffectation>> jour : parJour.entrySet()) {
            if (jour.getValue().get(0).getAnimateur().isMineurOn(jour.getKey())) {
                mineur = true;
                heuresMineures.merge(
                        Creneau.semaineIso(jour.getKey()),
                        EffectiveWork.minutes(jour.getValue(), parametres) / 60.0,
                        Double::sum);
            }
        }
        List<String> enDepassement = new ArrayList<>();
        Map<String, Double> parSemaine = new LinkedHashMap<>();
        for (String semaine : semaines) {
            double h = ligne.heuresParSemaine().getOrDefault(semaine, 0.0);
            parSemaine.put(semaine, h);
            double hMineur = heuresMineures.getOrDefault(semaine, 0.0);
            if (hMineur > plafondMineur + EPSILON || h - hMineur > plafondMajeur + EPSILON) {
                enDepassement.add(semaine);
            }
        }
        return new Personne(
                ligne.animateurId(),
                ligne.nom(),
                mineur,
                parSemaine,
                ligne.total(),
                mineur ? plafondMineur : plafondMajeur,
                enDepassement,
                ligne.heuresDimanche(),
                ligne.heuresJourFerie(),
                ligne.heuresNuit(),
                shortestRestHours(postes),
                ecarts);
    }

    /**
     * The shortest gap between the end of one worked day and the start of the
     * next one, in hours; {@code null} when fewer than two days are worked.
     * A seat crossing midnight ends past 24:00, as everywhere else.
     */
    static Double shortestRestHours(List<PosteAffectation> postes) {
        TreeMap<LocalDate, int[]> jours = new TreeMap<>();
        for (PosteAffectation poste : postes) {
            if (poste.heureDebutEffectif() == null || poste.getCreneau().getDate() == null) {
                continue;
            }
            int debut = poste.heureDebutEffectif().toSecondOfDay() / 60;
            int fin = debut + poste.getDureeEffectiveMinutes();
            jours.merge(poste.getCreneau().getDate(), new int[] {debut, fin}, (a, b) ->
                    new int[] {Math.min(a[0], b[0]), Math.max(a[1], b[1])});
        }
        Double min = null;
        LocalDate precedent = null;
        int[] precedentPlage = null;
        for (Map.Entry<LocalDate, int[]> jour : jours.entrySet()) {
            if (precedent != null) {
                long ecart = ChronoUnit.DAYS.between(precedent, jour.getKey());
                double repos = (ecart * 24 * 60 + jour.getValue()[0] - precedentPlage[1]) / 60.0;
                min = min == null ? repos : Math.min(min, repos);
            }
            precedent = jour.getKey();
            precedentPlage = jour.getValue();
        }
        return min;
    }

    /** The legal rules, counted, and the breaches filed under the animateur they name when it is unambiguous. */
    private List<Regle> rules(PlanningEvenement plan, Map<String, List<Breach>> parPersonne) {
        Map<String, ConstraintContribution> contributions = new LinkedHashMap<>();
        diagnostic.analyze(plan).contributions().forEach(c -> contributions.put(c.constraintName(), c));
        List<Regle> regles = new ArrayList<>();
        for (ConstraintDefinition definition : ConstraintCatalog.definitions()) {
            if (!definition.legale()) {
                continue;
            }
            ConstraintContribution contribution = contributions.get(definition.name());
            regles.add(new Regle(
                    definition.name(),
                    definition.libelleCourt(),
                    definition.categorie().contains("mineurs"),
                    contribution == null ? 0 : contribution.matchCount()));
            if (contribution == null) {
                continue;
            }
            Map<String, Integer> deja = new LinkedHashMap<>();
            for (var match : contribution.matches()) {
                var reference = ViolationFormatter.references(match.facts());
                if (reference.animateurId() != null
                        && deja.merge(reference.animateurId(), 1, Integer::sum) <= MAX_ECARTS_PAR_REGLE_ET_PERSONNE) {
                    parPersonne
                            .computeIfAbsent(reference.animateurId(), id -> new ArrayList<>())
                            .add(new Breach(definition.name(), definition.libelleCourt(), reference.texte()));
                }
            }
            // The lines are capped, the count is not: a capped list says how many it left out, so no
            // reader takes five lines for five breaches.
            deja.forEach((animateurId, total) -> {
                if (total > MAX_ECARTS_PAR_REGLE_ET_PERSONNE) {
                    parPersonne
                            .get(animateurId)
                            .add(new Breach(
                                    definition.name(),
                                    definition.libelleCourt(),
                                    "… et " + (total - MAX_ECARTS_PAR_REGLE_ET_PERSONNE)
                                            + " autre(s) écart(s) de cette règle"));
                }
            });
        }
        return regles;
    }

    private static Synthese synthese(List<Personne> personnes, List<Regle> regles) {
        return new Synthese(
                personnes.size(),
                (int) personnes.stream().filter(Personne::mineur).count(),
                regles.stream().mapToInt(Regle::ecarts).sum(),
                personnes.stream()
                        .mapToInt(p -> p.semainesEnDepassement().size())
                        .sum(),
                personnes.stream().mapToDouble(Personne::heuresTotal).sum(),
                personnes.stream().mapToDouble(Personne::heuresDimanche).sum(),
                personnes.stream().mapToDouble(Personne::heuresFerie).sum(),
                personnes.stream().mapToDouble(Personne::heuresNuit).sum());
    }

    private static List<String> differencesWithDefaults(ParametresLegaux p) {
        List<String> lignes = new ArrayList<>();
        diff(
                lignes,
                "Durée hebdomadaire maximale",
                p.getDureeHebdomadaireMaxMinutes(),
                ParametresLegaux.DUREE_HEBDOMADAIRE_MAX_MINUTES_PAR_DEFAUT);
        diff(
                lignes,
                "Durée hebdomadaire maximale des mineurs",
                p.getDureeHebdomadaireMaxMineurMinutes(),
                ParametresLegaux.DUREE_HEBDOMADAIRE_MAX_MINEUR_MINUTES_PAR_DEFAUT);
        diff(
                lignes,
                "Durée maximale d'une vacation",
                p.getDureeVacationMaxMinutes(),
                ParametresLegaux.DUREE_VACATION_MAX_MINUTES_PAR_DEFAUT);
        diff(
                lignes,
                "Repos quotidien minimal",
                p.getReposQuotidienMinimalMinutes(),
                ParametresLegaux.REPOS_QUOTIDIEN_MINIMAL_MINUTES_PAR_DEFAUT);
        return lignes;
    }

    private static void diff(List<String> lignes, String libelle, int minutes, int parDefaut) {
        if (minutes != parDefaut) {
            lignes.add(libelle + " : " + RapportConformite.hoursLabel(minutes / 60.0) + " au lieu de "
                    + RapportConformite.hoursLabel(parDefaut / 60.0) + " par défaut");
        }
    }
}
