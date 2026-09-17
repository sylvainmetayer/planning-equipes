package dev.sylvain.planning.service.scenario;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.ContrainteAdHoc;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.Emplacement;
import dev.sylvain.planning.domain.FenetreHoraire;
import dev.sylvain.planning.domain.FenetreRepas;
import dev.sylvain.planning.domain.HoraireStand;
import dev.sylvain.planning.domain.IndisponibiliteStand;
import dev.sylvain.planning.domain.JourneeType;
import dev.sylvain.planning.domain.NiveauCompetence;
import dev.sylvain.planning.domain.NiveauEffort;
import dev.sylvain.planning.domain.OuvertureStand;
import dev.sylvain.planning.domain.ParametresLegaux;
import dev.sylvain.planning.domain.ParametresQualite;
import dev.sylvain.planning.domain.ParametresSolveur;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.domain.TypeContrainteAdHoc;
import dev.sylvain.planning.domain.TypeJoursHoraire;
import dev.sylvain.planning.domain.VacationType;
import dev.sylvain.planning.scenario.dto.AnimateurDto;
import dev.sylvain.planning.scenario.dto.ContrainteAdHocDto;
import dev.sylvain.planning.scenario.dto.ContraintesDto;
import dev.sylvain.planning.scenario.dto.CreneauDto;
import dev.sylvain.planning.scenario.dto.EditionCibleDto;
import dev.sylvain.planning.scenario.dto.EmplacementDto;
import dev.sylvain.planning.scenario.dto.FenetreHoraireDto;
import dev.sylvain.planning.scenario.dto.HoraireStandDto;
import dev.sylvain.planning.scenario.dto.IndisponibiliteStandDto;
import dev.sylvain.planning.scenario.dto.JourneeTypeDto;
import dev.sylvain.planning.scenario.dto.OuvertureStandDto;
import dev.sylvain.planning.scenario.dto.ParametresLegauxDto;
import dev.sylvain.planning.scenario.dto.ParametresQualiteDto;
import dev.sylvain.planning.scenario.dto.ParametresSolveurDto;
import dev.sylvain.planning.scenario.dto.PosteDto;
import dev.sylvain.planning.scenario.dto.ScenarioDto;
import dev.sylvain.planning.scenario.dto.StandDto;
import dev.sylvain.planning.scenario.dto.TypologieDto;
import dev.sylvain.planning.scenario.dto.VacationTypeDto;
import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.referentiel.HoraireStandResolver;
import dev.sylvain.planning.service.referentiel.JourneesTypesMaterialisation;
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
 * number, a section that no longer exists, a window with no end.</p>
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
        // The meal windows the file declares, not the ones the database holds:
        // a scenario's vacations are laid out around its own windows, so it has
        // to be judged on the same ones. Reading them from the edition instead
        // scored a grid against another set — on festival-realiste-canicule, an
        // evening window deliberately placed at 17:00-18:00 (inert, it falls in
        // the gap) was scored as 19:00-21:00, in the middle of the evening
        // block, which no assignment could satisfy. A file that declares
        // nothing inherits the defaults, exactly like an edition that never
        // configured them.
        evenement.setFenetresRepas(
                FenetreRepas.from(parametresLegaux(scenario.parametresLegaux()).orElseGet(parametresLegauxParDefaut)));
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
            // A hand-written grid can say what the découpage says of the shifts
            // it generates itself: this slot covers a meal service, so the
            // stand runs at half its usual headcount on it
            // (Creneau#effectifRequis). Without it a grid entered as VACATIONS
            // — the very mode issue #438 is about — could describe a midday
            // rotation only by asking for two full crews where one relieves
            // the other.
            creneau.setCouverturePause(Boolean.TRUE.equals(creneauDto.couverturePause()));
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
                requiredName(dto.prenom(), "animateurs.prenom"),
                requiredName(dto.nom(), "animateurs.nom"),
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
                parametresQualite(scenario.parametresQualite()),
                parametresSolveur(scenario.parametresSolveur()),
                typologies(scenario.typologies()),
                edition(scenario),
                contraintes(scenario.contraintes()),
                journeesTypes(scenario.journeesTypes()));
    }

    /**
     * The {@code journeesTypes:} section: each template gets a provisional
     * negative id — the file has none — that its own dates name, and the
     * repository reassigns both when it writes them.
     */
    static Optional<ScenarioYamlReader.JourneesTypesScenario> journeesTypes(List<JourneeTypeDto> dtos) {
        if (dtos == null) {
            return Optional.empty();
        }
        List<JourneeType> journeesTypes = new ArrayList<>();
        List<JourneesTypesMaterialisation.Affectation> calendrier = new ArrayList<>();
        long provisoire = -1;
        for (JourneeTypeDto dto : dtos) {
            List<VacationType> vacations = new ArrayList<>();
            for (VacationTypeDto vacation : required(dto.vacations(), "journeesTypes.vacations")) {
                vacations.add(new VacationType(
                        required(vacation.heureDebut(), "journeesTypes.vacations.heureDebut"),
                        required(vacation.heureFin(), "journeesTypes.vacations.heureFin"),
                        Boolean.TRUE.equals(vacation.couverturePause())));
            }
            JourneeType journeeType = new JourneeType(provisoire, required(dto.nom(), "journeesTypes.nom"), vacations);
            journeesTypes.add(journeeType);
            if (dto.dates() != null) {
                for (LocalDate date : dto.dates()) {
                    calendrier.add(new JourneesTypesMaterialisation.Affectation(date, provisoire));
                }
            }
            provisoire--;
        }
        return Optional.of(new ScenarioYamlReader.JourneesTypesScenario(journeesTypes, calendrier));
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
            typologies.add(new TypologieItem(
                    dto.id(), dto.label(), Boolean.TRUE.equals(dto.ninja()), dto.maxCreneauxParAnimateur(), null));
        }
        return typologies;
    }

    /** The legal parameters, meal break and vacation ceiling included. */
    private static Optional<ParametresLegaux> parametresLegaux(ParametresLegauxDto dto) {
        if (dto == null) {
            return Optional.empty();
        }
        ParametresLegaux parametres = new ParametresLegaux();
        setInt(dto.dureeHebdomadaireMaxMinutes(), parametres::setDureeHebdomadaireMaxMinutes);
        setInt(dto.pauseMinimaleEntreVacationsMinutes(), parametres::setPauseMinimaleEntreVacationsMinutes);
        setInt(dto.dureeVacationMaxMinutes(), parametres::setDureeVacationMaxMinutes);
        setInt(dto.reposQuotidienMinimalMinutes(), parametres::setReposQuotidienMinimalMinutes);
        if (dto.pauseSurPoste() != null) {
            parametres.setPauseSurPoste(dto.pauseSurPoste());
        }
        setInt(dto.coupureRepasMinutes(), parametres::setCoupureRepasMinutes);
        set(dto.coupureRepasMidiDebut(), parametres::setCoupureRepasMidiDebut);
        set(dto.coupureRepasMidiFin(), parametres::setCoupureRepasMidiFin);
        set(dto.coupureRepasSoirDebut(), parametres::setCoupureRepasSoirDebut);
        set(dto.coupureRepasSoirFin(), parametres::setCoupureRepasSoirFin);
        setInt(dto.dureePauseMajeurMinutes(), parametres::setDureePauseMajeurMinutes);
        setInt(dto.dureePauseMineurMinutes(), parametres::setDureePauseMineurMinutes);
        set(dto.heureDebutSoiree(), parametres::setHeureDebutSoiree);
        return Optional.of(parametres);
    }

    /**
     * {@code parametresQualite:} — the thresholds the file was verified with.
     * The two hours are read wholesale: a section that names neither describes
     * an edition where {@code eviterFermeturePuisOuverture} says nothing, which
     * is a legitimate tuning and not an omission. The numbers a file leaves out
     * keep the importing edition's value.
     */
    private static Optional<ParametresQualite> parametresQualite(ParametresQualiteDto dto) {
        if (dto == null) {
            return Optional.empty();
        }
        ParametresQualite defauts = new ParametresQualite();
        return Optional.of(new ParametresQualite(
                dto.maxEmplacementsDistinctsParJour() != null
                        ? dto.maxEmplacementsDistinctsParJour()
                        : defauts.maxEmplacementsDistinctsParJour(),
                dto.heureServiceTardif(),
                dto.heureServiceMatinal(),
                dto.reposSouhaiteApresServiceTardifMinutes() != null
                        ? dto.reposSouhaiteApresServiceTardifMinutes()
                        : defauts.reposSouhaiteApresServiceTardifMinutes(),
                dto.typologiesDistinctesMax() != null
                        ? dto.typologiesDistinctesMax()
                        : defauts.typologiesDistinctesMax()));
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
        Set<String> activees = new LinkedHashSet<>();
        if (dto.activees() != null) {
            for (String nom : dto.activees()) {
                activees.add(requireKnownConstraint(String.valueOf(nom)));
            }
        }
        Set<String> contradictoires = new LinkedHashSet<>(desactivees);
        contradictoires.retainAll(activees);
        if (!contradictoires.isEmpty()) {
            throw new BusinessError.Invalid("La section contraintes cite « "
                    + String.join(" », « ", contradictoires)
                    + " » à la fois dans desactivees et dans activees : "
                    + "le fichier ne dit pas quel problème il décrit.");
        }
        Map<String, Integer> poids = new LinkedHashMap<>();
        if (dto.poids() != null) {
            for (Map.Entry<String, Integer> entry : dto.poids().entrySet()) {
                if (entry.getValue() != null) {
                    poids.put(requireKnownConstraint(entry.getKey()), entry.getValue());
                }
            }
        }
        return Optional.of(new ContraintesScenario(desactivees, activees, poids));
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

    /**
     * A prénom or a nom: blank is as missing as absent. The fiche form, MCP and
     * the CSV import already refuse a fiche without both; the scenario file
     * was the one way in, and a fiche named by a single word is one nobody
     * recognises on a printed planning.
     */
    private static String requiredName(String valeur, String champ) {
        if (valeur == null || valeur.isBlank()) {
            throw new BusinessError.Invalid("Champ manquant: " + champ);
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
