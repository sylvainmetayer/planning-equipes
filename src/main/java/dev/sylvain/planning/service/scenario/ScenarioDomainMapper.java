package dev.sylvain.planning.service.scenario;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.ContrainteAdHoc;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.Emplacement;
import dev.sylvain.planning.domain.FenetreHoraire;
import dev.sylvain.planning.domain.HoraireStand;
import dev.sylvain.planning.domain.IndisponibiliteStand;
import dev.sylvain.planning.domain.NiveauCompetence;
import dev.sylvain.planning.domain.NiveauEffort;
import dev.sylvain.planning.domain.OuvertureStand;
import dev.sylvain.planning.domain.ParametresDecoupage;
import dev.sylvain.planning.domain.ParametresLegaux;
import dev.sylvain.planning.domain.ParametresSolveur;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.domain.TypeContrainteAdHoc;
import dev.sylvain.planning.domain.TypeJoursHoraire;
import dev.sylvain.planning.scenario.dto.AnimateurDto;
import dev.sylvain.planning.scenario.dto.ContrainteAdHocDto;
import dev.sylvain.planning.scenario.dto.ContraintesDto;
import dev.sylvain.planning.scenario.dto.CreneauDto;
import dev.sylvain.planning.scenario.dto.EditionCibleDto;
import dev.sylvain.planning.scenario.dto.EmplacementDto;
import dev.sylvain.planning.scenario.dto.FenetreHoraireDto;
import dev.sylvain.planning.scenario.dto.HoraireStandDto;
import dev.sylvain.planning.scenario.dto.IndisponibiliteStandDto;
import dev.sylvain.planning.scenario.dto.OuvertureStandDto;
import dev.sylvain.planning.scenario.dto.ParametresDecoupageDto;
import dev.sylvain.planning.scenario.dto.ParametresLegauxDto;
import dev.sylvain.planning.scenario.dto.ParametresSolveurDto;
import dev.sylvain.planning.scenario.dto.PosteDto;
import dev.sylvain.planning.scenario.dto.ScenarioDto;
import dev.sylvain.planning.scenario.dto.StandDto;
import dev.sylvain.planning.scenario.dto.TypologieDto;
import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.referentiel.HoraireStandResolver;
import dev.sylvain.planning.service.referentiel.TypologieItem;
import dev.sylvain.planning.service.scenario.ScenarioYamlReader.ContraintesScenario;
import dev.sylvain.planning.service.scenario.ScenarioYamlReader.ReferenceScenario;
import dev.sylvain.planning.service.scenario.ScenarioYamlReader.ScenarioSections;
import dev.sylvain.planning.service.solve.ProblemBuilder;
import dev.sylvain.planning.solver.ConstraintCatalog;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

/**
 * From the file's shape to the domain: a {@link ScenarioDto}, bound by
 * {@code ScenarioBinder}, becomes the referential, the planning and the
 * optional sections the import applies.
 *
 * <p>This is the second half of issue #392's A2. The reader used to walk a
 * {@code Map<String, Object>} by hand — 590 lines of casts, each one a place
 * where the file could say something the code did not expect and get a
 * {@code ClassCastException} for an answer. Types are the binder's business
 * now, and it names the path of what it refuses; what is left here is the
 * <b>meaning</b>: which section is required, which absent value means
 * "until closing time", which id a constraint points at, and the twenty-odd
 * refusals a screen shows as written.</p>
 *
 * <p>Two things this mapping does <em>not</em> do, deliberately. It does not
 * run bean validation — {@code ScenarioValidator} does, on the same DTO, and
 * an import that refused what the validator accepts would make the Validateur
 * screen a liar. And it keeps the reader's leniencies where a stricter rule
 * would refuse files the application itself produced: an id written as a
 * number, a bare {@code decoupageAuto:}, a window with no end.</p>
 */
final class ScenarioDomainMapper {

    private ScenarioDomainMapper() {}

    /* ------------------------------ planning ------------------------------ */

    /**
     * The whole problem a file describes, seats included — generated from
     * stands × créneaux when the file lists none, taken as written otherwise.
     */
    static PlanningEvenement planning(ScenarioDto scenario, Supplier<ParametresLegaux> parametresLegauxParDefaut) {
        ReferenceScenario reference = reference(scenario);

        // Expand the recurring opening hours before deciding anything about
        // openings: a file may describe the opening hours of a stand as rules
        // rather than as dated windows, and they must be resolved on the days of
        // its own timeslots. With no rule, the call changes nothing.
        HoraireStandResolver.apply(
                reference.standsById().values(), reference.creneauxParId().values());

        List<PosteAffectation> postes = scenario.postes() == null
                ? ProblemBuilder.buildPostes(
                        new ArrayList<>(reference.standsById().values()),
                        new ArrayList<>(reference.creneauxParId().values()))
                : postes(scenario.postes(), reference);

        // Exactly the file's own ad hoc constraints, and nothing else: a
        // scenario describes the whole problem, and re-importing it must not
        // merge somebody else's. A file carrying no section carries none.
        // (It used to fall back on the database's — the *current* edition's —
        // which leaked one edition's exceptions into another.)
        List<ContrainteAdHoc> contraintesAdHoc = scenario.contraintesAdHoc() == null
                ? new ArrayList<>()
                : contraintesAdHoc(scenario.contraintesAdHoc(), reference);
        PlanningEvenement evenement =
                new PlanningEvenement(reference.dateDebut(), reference.animateurs(), postes, contraintesAdHoc);
        evenement.setParametresLegaux(
                List.of(parametresLegaux(scenario.parametresLegaux()).orElseGet(parametresLegauxParDefaut)));
        // Same reasoning as the ad hoc constraints above, for the dosage: a file
        // that pins its weights describes the problem it was verified against,
        // and solving it must apply them whether or not it was ever imported.
        evenement.setPonderationsScenario(contraintes(scenario.contraintes())
                .map(ContraintesScenario::poids)
                .orElse(null));
        return evenement;
    }

    private static List<PosteAffectation> postes(List<PosteDto> postesDto, ReferenceScenario reference) {
        List<PosteAffectation> postes = new ArrayList<>();
        for (PosteDto posteDto : postesDto) {
            Stand stand = reference.standsById().get(posteDto.standId());
            Creneau creneau = reference.creneauxParId().get(posteDto.creneauId());

            PosteAffectation poste = new PosteAffectation(posteDto.id(), stand, creneau);
            // Mirrors buildPostes(): a hand-authored poste can still name a
            // créneau the stand is only partially open for (IndisponibiliteStand /
            // OuvertureStand), so narrow its effective window the same way instead
            // of silently using the créneau's full amplitude.
            List<int[]> segments = creneau.segmentsOuvertsMinutes(stand);
            if (segments.size() == 1) {
                int[] segment = segments.get(0);
                boolean creneauEntierOuvert = segment[0] == 0 && segment[1] == creneau.getDureeMinutes();
                if (!creneauEntierOuvert) {
                    poste.setHeureDebutEffective(ProblemBuilder.shift(creneau.getHeureDebut(), segment[0]));
                    poste.setHeureFinEffective(ProblemBuilder.shift(creneau.getHeureDebut(), segment[1]));
                }
            }
            postes.add(poste);
        }
        return postes;
    }

    /* ---------------------------- referential ----------------------------- */

    /** {@code creneaux}/{@code stands}/{@code animateurs} sections, cross-linked. */
    static ReferenceScenario reference(ScenarioDto scenario) {
        // The créneaux carry a historical text id (used only to tie postes and
        // créneaux together), replaced here by a synthetic numeric id; jour is
        // recomputed (see Creneau.assignerJours), the value from the file is ignored.
        Map<String, Creneau> creneauxParId = new HashMap<>();
        long compteurCreneauId = 1;
        for (CreneauDto creneauDto : required(scenario.creneaux(), "creneaux")) {
            Creneau creneau = new Creneau(
                    compteurCreneauId++,
                    0,
                    required(creneauDto.date(), "creneaux.date"),
                    required(creneauDto.heureDebut(), "creneaux.heureDebut"),
                    required(creneauDto.heureFin(), "creneaux.heureFin"));
            creneauxParId.put(creneauDto.id(), creneau);
        }
        Creneau.assignerJours(creneauxParId.values());

        Map<String, Emplacement> emplacementsParId = new HashMap<>();
        if (scenario.emplacements() != null) {
            for (EmplacementDto dto : scenario.emplacements()) {
                emplacementsParId.put(dto.id(), new Emplacement(dto.id(), dto.nom(), dto.latitude(), dto.longitude()));
            }
        }

        Map<String, Stand> standsParId = new HashMap<>();
        List<StandDto> standsDto = required(scenario.stands(), "stands");
        for (int i = 0; i < standsDto.size(); i++) {
            Stand stand = stand(standsDto.get(i), i, emplacementsParId);
            standsParId.put(stand.getId(), stand);
        }

        List<Animateur> animateurs = new ArrayList<>();
        for (AnimateurDto dto : required(scenario.animateurs(), "animateurs")) {
            animateurs.add(animateur(dto));
        }
        // A scenario carries its own typologie referential, so the ninja typologie
        // comes from the file itself — the database one may not be loaded yet (or
        // may describe a different event entirely).
        String typologieNinja = typologies(scenario.typologies()).stream()
                .filter(TypologieItem::ninja)
                .map(TypologieItem::id)
                .findFirst()
                .orElse(null);
        animateurs.forEach(animateur -> animateur.applyNinjaTypologie(typologieNinja));

        LocalDate dateDebut = required(required(scenario.festival(), "festival").dateDebut(), "festival.dateDebut");
        return new ReferenceScenario(dateDebut, creneauxParId, standsParId, animateurs);
    }

    private static Stand stand(StandDto dto, int index, Map<String, Emplacement> emplacementsParId) {
        if (dto.typologiesProposees() == null || dto.typologiesProposees().isEmpty()) {
            // Same rule as StandValidator (issue #343), said in the file's terms.
            throw new BusinessError.Invalid("stands[" + index + "] (id " + dto.id()
                    + ") : aucune typologie proposée, un stand est toujours rattaché à au moins une typologie");
        }
        Stand stand = new Stand(
                dto.id(),
                dto.nom(),
                new HashSet<>(dto.typologiesProposees()),
                required(dto.effectifMin(), "stands.effectifMin"),
                required(dto.effectifMax(), "stands.effectifMax"),
                Boolean.TRUE.equals(dto.reserveMajeurs()),
                Boolean.TRUE.equals(dto.premium()));
        stand.setNiveauEffort(dto.niveauEffort() == null ? NiveauEffort.NORMAL : dto.niveauEffort());
        stand.setFamille(dto.famille());
        if (dto.emplacementId() != null) {
            stand.setEmplacement(emplacementsParId.get(dto.emplacementId()));
        }
        if (dto.indisponibilites() != null) {
            List<IndisponibiliteStand> indisponibilites = new ArrayList<>();
            for (IndisponibiliteStandDto indispo : dto.indisponibilites()) {
                indisponibilites.add(new IndisponibiliteStand(
                        null,
                        required(indispo.date(), "stands.indisponibilites.date"),
                        required(indispo.heureDebut(), "stands.indisponibilites.heureDebut"),
                        indispo.heureFin(),
                        indispo.motif()));
            }
            stand.setIndisponibilites(indisponibilites);
        }
        if (dto.ouvertures() != null) {
            List<OuvertureStand> ouvertures = new ArrayList<>();
            for (OuvertureStandDto ouverture : dto.ouvertures()) {
                ouvertures.add(new OuvertureStand(
                        null,
                        required(ouverture.date(), "stands.ouvertures.date"),
                        required(ouverture.heureDebut(), "stands.ouvertures.heureDebut"),
                        ouverture.heureFin(),
                        ouverture.motif(),
                        ouverture.effectif()));
            }
            stand.setOuvertures(ouvertures);
        }
        if (dto.horaires() != null) {
            stand.setHoraires(horaires(dto.horaires()));
        }
        return stand;
    }

    /**
     * The {@code horaires:} of a stand — recurring rules, with the day selector
     * flattened onto the rule (see {@code ScenarioDtoAssembler}). An absent
     * {@code jours} reads as {@link TypeJoursHoraire#TOUS}, which is what makes
     * the common case a two-line entry; an absent window end means "until
     * closing time" (see {@link FenetreHoraire}).
     */
    private static List<HoraireStand> horaires(List<HoraireStandDto> horairesDto) {
        List<HoraireStand> horaires = new ArrayList<>();
        for (HoraireStandDto dto : horairesDto) {
            HoraireStand horaire = new HoraireStand();
            if (dto.mode() == null) {
                throw new BusinessError.Invalid("Champ manquant: stands.horaires.mode (OUVERTURE ou FERMETURE)");
            }
            horaire.setMode(dto.mode());
            horaire.setJours(dto.jours() == null ? TypeJoursHoraire.TOUS : dto.jours());
            if (dto.joursSemaine() != null) {
                horaire.setJoursSemaine(new TreeSet<DayOfWeek>(dto.joursSemaine()));
            }
            horaire.setDateDebut(dto.dateDebut());
            horaire.setDateFin(dto.dateFin());
            if (dto.dates() != null) {
                horaire.setDates(new TreeSet<LocalDate>(dto.dates()));
            }
            if (dto.fenetres() == null || dto.fenetres().isEmpty()) {
                throw new BusinessError.Invalid("Champ manquant: stands.horaires.fenetres (au moins une fenêtre)");
            }
            List<FenetreHoraire> fenetres = new ArrayList<>();
            for (FenetreHoraireDto fenetre : dto.fenetres()) {
                if (fenetre.heureDebut() == null) {
                    throw new BusinessError.Invalid("Champ manquant: stands.horaires.fenetres.heureDebut");
                }
                fenetres.add(new FenetreHoraire(fenetre.heureDebut(), fenetre.heureFin(), fenetre.effectif()));
            }
            horaire.setFenetres(fenetres);
            horaire.setMotif(dto.motif());
            horaires.add(horaire);
        }
        return horaires;
    }

    private static Animateur animateur(AnimateurDto dto) {
        Animateur animateur = new Animateur(
                dto.id(),
                dto.prenom(),
                dto.nom(),
                required(dto.dateNaissance(), "animateurs.dateNaissance"),
                Boolean.TRUE.equals(dto.manager()));
        animateur.setEmail(dto.email());
        Map<String, NiveauCompetence> competences = new HashMap<>();
        if (dto.competences() != null) {
            competences.putAll(dto.competences());
        }
        animateur.setCompetences(competences);
        // Days off are an opt-out: available unless listed.
        animateur.setJoursIndisponibles(
                dto.joursIndisponibles() == null ? new HashSet<>() : new HashSet<>(dto.joursIndisponibles()));
        animateur.setSouhaits(dto.souhaits() == null ? new HashSet<>() : new HashSet<>(dto.souhaits()));
        return animateur;
    }

    /**
     * {@code contraintesAdHoc:}, resolving the file's own animateur, stand and
     * créneau ids against the sections already mapped.
     *
     * <p>The créneau reference goes through {@code creneauxParId}: a
     * scenario's créneaux carry a text id in the file and get a synthetic
     * numeric one here (then a fresh database one on import, remapped by
     * {@code ReferenceDataImportRepository}). Resolving it any later would
     * leave the constraint pointing at nothing.</p>
     */
    private static List<ContrainteAdHoc> contraintesAdHoc(List<ContrainteAdHocDto> dtos, ReferenceScenario reference) {
        List<ContrainteAdHoc> contraintes = new ArrayList<>();
        for (ContrainteAdHocDto dto : dtos) {
            String id = dto.id();
            if (id == null || id.isBlank()) {
                throw new BusinessError.Invalid("Chaque contrainte ad hoc doit porter un id non vide.");
            }
            if (dto.type() == null) {
                // The column is NOT NULL: letting it through would fail in SQL,
                // after the parameter sections were already written.
                throw new BusinessError.Invalid("La contrainte ad hoc " + id + " ne dit pas son type ("
                        + String.join(
                                ", ",
                                Arrays.stream(TypeContrainteAdHoc.values())
                                        .map(Enum::name)
                                        .toList())
                        + ").");
            }
            ContrainteAdHoc contrainte = new ContrainteAdHoc(id, dto.type());
            if (dto.animateurs() != null) {
                for (String animateurId : dto.animateurs()) {
                    contrainte
                            .getAnimateursConcernes()
                            .add(reference.animateurs().stream()
                                    .filter(animateur -> animateur.getId().equals(animateurId))
                                    .findFirst()
                                    .orElseThrow(() -> new BusinessError.Invalid("La contrainte ad hoc " + id
                                            + " vise l'animateur " + animateurId + ", absent du scénario.")));
                }
            }
            if (dto.creneauId() != null) {
                Creneau creneau = reference.creneauxParId().get(dto.creneauId());
                if (creneau == null) {
                    throw new BusinessError.Invalid("La contrainte ad hoc " + id + " vise le créneau " + dto.creneauId()
                            + ", absent du scénario.");
                }
                contrainte.setCreneau(creneau);
            }
            if (dto.standId() != null) {
                Stand stand = reference.standsById().get(dto.standId());
                if (stand == null) {
                    throw new BusinessError.Invalid(
                            "La contrainte ad hoc " + id + " vise le stand " + dto.standId() + ", absent du scénario.");
                }
                contrainte.setStand(stand);
            }
            contrainte.setRaison(dto.raison());
            contraintes.add(contrainte);
        }
        return contraintes;
    }

    /* ------------------------------ sections ------------------------------ */

    /** The optional sections a file pins, without building its planning. */
    static ScenarioSections sections(ScenarioDto scenario) {
        return new ScenarioSections(
                parametresLegaux(scenario.parametresLegaux()),
                parametresDecoupage(scenario.parametresDecoupage()),
                parametresSolveur(scenario.parametresSolveur()),
                // Presence, not content: `decoupageAuto: {}`, a bare `decoupageAuto:`
                // and its historical groupe fields all mean "slice on import";
                // ScenarioBinder turns an explicit `decoupageAuto: false` into absence.
                scenario.decoupageAuto() != null,
                typologies(scenario.typologies()),
                edition(scenario),
                contraintes(scenario.contraintes()));
    }

    /** The {@code edition:} section, when the file names its target. */
    static Optional<EditionCibleDto> edition(ScenarioDto scenario) {
        EditionCibleDto edition = scenario.edition();
        if (edition == null) {
            return Optional.empty();
        }
        if (edition.id() == null || edition.id().isBlank()) {
            throw new BusinessError.Invalid("La section edition exige un champ id non vide.");
        }
        return Optional.of(edition);
    }

    static List<TypologieItem> typologies(List<TypologieDto> dtos) {
        if (dtos == null) {
            return List.of();
        }
        List<TypologieItem> typologies = new ArrayList<>();
        for (TypologieDto dto : dtos) {
            typologies.add(new TypologieItem(dto.id(), dto.label(), Boolean.TRUE.equals(dto.ninja())));
        }
        return typologies;
    }

    private static Optional<ParametresLegaux> parametresLegaux(ParametresLegauxDto dto) {
        if (dto == null) {
            return Optional.empty();
        }
        ParametresLegaux parametres = new ParametresLegaux();
        setInt(dto.dureeHebdomadaireMaxMinutes(), parametres::setDureeHebdomadaireMaxMinutes);
        setInt(dto.pauseMinimaleEntreVacationsMinutes(), parametres::setPauseMinimaleEntreVacationsMinutes);
        setInt(dto.reposQuotidienMinimalMinutes(), parametres::setReposQuotidienMinimalMinutes);
        if (dto.pauseSurPoste() != null) {
            parametres.setPauseSurPoste(dto.pauseSurPoste());
        }
        return Optional.of(parametres);
    }

    private static Optional<ParametresDecoupage> parametresDecoupage(ParametresDecoupageDto dto) {
        if (dto == null) {
            return Optional.empty();
        }
        ParametresDecoupage parametres = new ParametresDecoupage();
        setInt(dto.dureeVacationCibleMinutes(), parametres::setDureeVacationCibleMinutes);
        setInt(dto.dureeVacationMinMinutes(), parametres::setDureeVacationMinMinutes);
        setInt(dto.dureeVacationMaxMinutes(), parametres::setDureeVacationMaxMinutes);
        setInt(dto.dureeChevauchementMinutes(), parametres::setDureeChevauchementMinutes);
        setInt(dto.dureePauseRepasMinutes(), parametres::setDureePauseRepasMinutes);
        set(dto.fenetreRepasMidiDebut(), parametres::setFenetreRepasMidiDebut);
        set(dto.fenetreRepasMidiFin(), parametres::setFenetreRepasMidiFin);
        set(dto.fenetreRepasSoirDebut(), parametres::setFenetreRepasSoirDebut);
        set(dto.fenetreRepasSoirFin(), parametres::setFenetreRepasSoirFin);
        set(dto.strategieCouverturePendantPause(), parametres::setStrategieCouverturePendantPause);
        setInt(dto.nombreFamillesDecalage(), parametres::setNombreFamillesDecalage);
        setInt(dto.dureeDecalageMaxMinutes(), parametres::setDureeDecalageMaxMinutes);
        set(dto.modeGrille(), parametres::setModeGrille);
        return Optional.of(parametres);
    }

    private static Optional<ParametresSolveur> parametresSolveur(ParametresSolveurDto dto) {
        if (dto == null || dto.dureeResolutionSecondes() == null) {
            return Optional.empty();
        }
        return Optional.of(new ParametresSolveur(dto.dureeResolutionSecondes()));
    }

    /**
     * {@code contraintes:} — {@code desactivees:} (constraint names) and
     * {@code poids:} (name → weight). An unknown name is refused rather than
     * ignored: it means either a typo or a file written against another
     * version of the catalogue, and silently dropping it would leave the
     * operator convinced a rule was switched off — or dosed — when it never was.
     */
    private static Optional<ContraintesScenario> contraintes(ContraintesDto dto) {
        if (dto == null) {
            return Optional.empty();
        }
        Set<String> desactivees = new LinkedHashSet<>();
        if (dto.desactivees() != null) {
            for (String nom : dto.desactivees()) {
                desactivees.add(requireKnownConstraint(String.valueOf(nom)));
            }
        }
        Map<String, Integer> poids = new LinkedHashMap<>();
        if (dto.poids() != null) {
            for (Map.Entry<String, Integer> entry : dto.poids().entrySet()) {
                if (entry.getValue() != null) {
                    poids.put(requireKnownConstraint(entry.getKey()), entry.getValue());
                }
            }
        }
        return Optional.of(new ContraintesScenario(desactivees, poids));
    }

    private static String requireKnownConstraint(String nom) {
        if (!ConstraintCatalog.PAR_NOM.containsKey(nom)) {
            throw new BusinessError.Invalid(
                    "La section contraintes cite « " + nom + " », qui n'est pas une contrainte du catalogue.");
        }
        return nom;
    }

    /* ------------------------------- helpers ------------------------------ */

    /** Applies a pinned value to its setter, leaving the target's own default when the file says nothing. */
    private static void setInt(Integer valeur, IntConsumer setter) {
        if (valeur != null) {
            setter.accept(valeur);
        }
    }

    private static <T> void set(T valeur, Consumer<T> setter) {
        if (valeur != null) {
            setter.accept(valeur);
        }
    }

    /** A section or a field the file must carry, named the way the reader always named it. */
    private static <T> T required(T valeur, String champ) {
        if (valeur == null) {
            boolean date = champ.endsWith("date") || champ.endsWith("dateDebut") || champ.endsWith("dateNaissance");
            throw new BusinessError.Invalid((date ? "Champ date manquant: " : "Section ou champ manquant: ") + champ);
        }
        return valeur;
    }

    /** A {@link LocalTime} the file must carry. */
    private static LocalTime required(LocalTime valeur, String champ) {
        if (valeur == null) {
            throw new BusinessError.Invalid("Champ manquant: " + champ);
        }
        return valeur;
    }
}
