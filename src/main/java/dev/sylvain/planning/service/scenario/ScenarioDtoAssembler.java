package dev.sylvain.planning.service.scenario;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.ContrainteAdHoc;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.Emplacement;
import dev.sylvain.planning.domain.FenetreHoraire;
import dev.sylvain.planning.domain.HoraireStand;
import dev.sylvain.planning.domain.IndisponibiliteStand;
import dev.sylvain.planning.domain.JourneeType;
import dev.sylvain.planning.domain.OuvertureStand;
import dev.sylvain.planning.domain.ParametresLegaux;
import dev.sylvain.planning.domain.ParametresQualite;
import dev.sylvain.planning.domain.ParametresSolveur;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.scenario.dto.AnimateurDto;
import dev.sylvain.planning.scenario.dto.ContrainteAdHocDto;
import dev.sylvain.planning.scenario.dto.ContraintesDto;
import dev.sylvain.planning.scenario.dto.CreneauDto;
import dev.sylvain.planning.scenario.dto.EmplacementDto;
import dev.sylvain.planning.scenario.dto.FenetreHoraireDto;
import dev.sylvain.planning.scenario.dto.FestivalDto;
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
import dev.sylvain.planning.service.referentiel.JourneesTypesMaterialisation;
import dev.sylvain.planning.service.referentiel.TypologieItem;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * The domain, as the one shape a scenario has.
 *
 * <p>This is the half of A2 (issue #392) that makes the format true by
 * construction: once the export serialises a {@link ScenarioDto}, what the
 * application writes <b>is</b> the DTO, and the schema published from it stops
 * being a parallel description of a format built somewhere else by hand.</p>
 *
 * <p>What used to be spread over a dozen {@code xxxYaml} methods building
 * {@code LinkedHashMap}s key by key lives here as one mapping per entity, and
 * the decisions those methods encoded in their {@code put} calls are now
 * expressed by leaving a component null — {@link
 * dev.sylvain.planning.scenario.ScenarioYaml#writer()} omits it.</p>
 */
final class ScenarioDtoAssembler {

    private ScenarioDtoAssembler() {}

    static ScenarioDto assemble(ScenarioYamlWriter.ScenarioExport export) {
        List<Creneau> creneaux = export.creneaux();
        LocalDate dateDebut = creneaux.stream()
                .map(Creneau::getDate)
                .filter(Objects::nonNull)
                .min(LocalDate::compareTo)
                .orElse(null);

        return new ScenarioDto(
                null,
                new FestivalDto(dateDebut),
                parametresSolveur(export.parametresSolveur()),
                parametresLegaux(export.parametresLegaux()),
                parametresQualite(export.parametresQualite()),
                contraintes(export),
                nullWhenEmpty(typologies(export.typologies())),
                creneaux(creneaux),
                nullWhenEmpty(journeesTypes(export.journeesTypes(), export.calendrierJourneesTypes())),
                nullWhenEmpty(emplacements(export.emplacements())),
                stands(export.stands()),
                animateurs(export.animateurs()),
                export.postes() == null ? null : postes(export.postes()),
                nullWhenEmpty(contraintesAdHoc(export.contraintesAdHoc(), creneaux)));
    }

    private static <T> List<T> nullWhenEmpty(List<T> liste) {
        return liste == null || liste.isEmpty() ? null : liste;
    }

    /**
     * The id is a {@code Long} in the database, but the published schema
     * declares it {@code string} and the reader reads it back as one. Written
     * raw it produced a YAML number no import could read — see the round trip
     * covered by {@code PlanningServiceScenarioAllerRetourTest}.
     */
    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }

    private static List<CreneauDto> creneaux(List<Creneau> creneaux) {
        return creneaux.stream()
                .map(creneau -> new CreneauDto(
                        asString(creneau.getId()),
                        creneau.getJour(),
                        creneau.getDate(),
                        creneau.getHeureDebut(),
                        creneau.getHeureFin(),
                        // Written only when true: a key absent from nine
                        // créneaux out of ten would be noise in a file people
                        // read.
                        creneau.isCouverturePause() ? Boolean.TRUE : null))
                .toList();
    }

    /** Each template with the dates the calendar gives it, in calendar order — what the import reads back. */
    private static List<JourneeTypeDto> journeesTypes(
            List<JourneeType> journeesTypes, List<JourneesTypesMaterialisation.Affectation> calendrier) {
        if (journeesTypes == null) {
            return List.of();
        }
        return journeesTypes.stream()
                .map(journeeType -> new JourneeTypeDto(
                        journeeType.getNom(),
                        journeeType.getVacations().stream()
                                .map(vacation -> new VacationTypeDto(
                                        vacation.heureDebut(),
                                        vacation.heureFin(),
                                        vacation.couverturePause() ? Boolean.TRUE : null))
                                .toList(),
                        calendrier == null
                                ? List.of()
                                : calendrier.stream()
                                        .filter(affectation ->
                                                affectation.journeeTypeId().equals(journeeType.getId()))
                                        .map(JourneesTypesMaterialisation.Affectation::date)
                                        .sorted()
                                        .toList()))
                .toList();
    }

    private static List<StandDto> stands(List<Stand> stands) {
        return stands.stream()
                .map(stand -> new StandDto(
                        stand.getId(),
                        stand.getNom(),
                        stand.getEmplacement() == null
                                ? null
                                : stand.getEmplacement().getId(),
                        new ArrayList<>(stand.getTypologiesProposees()),
                        stand.getEffectifMin(),
                        stand.getEffectifMax(),
                        stand.isReserveMajeurs(),
                        stand.isPremium(),
                        stand.getNiveauEffort(),
                        indisponibilites(stand.getIndisponibilites()),
                        ouvertures(stand.getOuvertures()),
                        horaires(stand.getHoraires())))
                .toList();
    }

    private static List<AnimateurDto> animateurs(List<Animateur> animateurs) {
        return animateurs.stream()
                .map(animateur -> new AnimateurDto(
                        animateur.getId(),
                        animateur.getPrenom(),
                        animateur.getNom(),
                        animateur.getDateNaissance(),
                        animateur.isManager(),
                        // Contact only — the espace-animateur access token never
                        // travels through a scenario file (regenerated from the
                        // database instead).
                        animateur.getEmail() == null || animateur.getEmail().isBlank() ? null : animateur.getEmail(),
                        animateur.getCompetences() == null ? Map.of() : new LinkedHashMap<>(animateur.getCompetences()),
                        animateur.getJoursIndisponibles() == null
                                ? List.of()
                                : animateur.getJoursIndisponibles().stream()
                                        .sorted()
                                        .toList(),
                        animateur.getSouhaits() == null ? List.of() : new ArrayList<>(animateur.getSouhaits())))
                .toList();
    }

    private static List<PosteDto> postes(List<PosteAffectation> postes) {
        return postes.stream()
                .map(poste -> new PosteDto(
                        poste.getId(),
                        poste.getStand().getId(),
                        asString(poste.getCreneau().getId()),
                        null))
                .toList();
    }

    private static List<IndisponibiliteStandDto> indisponibilites(List<IndisponibiliteStand> indisponibilites) {
        return indisponibilites.stream()
                .map(indispo -> new IndisponibiliteStandDto(
                        indispo.getDate(), indispo.getHeureDebut(), indispo.getHeureFin(), indispo.getMotif()))
                .toList();
    }

    private static List<OuvertureStandDto> ouvertures(List<OuvertureStand> ouvertures) {
        return ouvertures.stream()
                .map(ouverture -> new OuvertureStandDto(
                        ouverture.getDate(),
                        ouverture.getHeureDebut(),
                        ouverture.getHeureFin(),
                        ouverture.getMotif(),
                        ouverture.getEffectif()))
                .toList();
    }

    /**
     * The day selector is flattened onto the rule, and only the fields the
     * selector actually uses are carried: a {@code TOUS} rule has no dates, and
     * a component left null is a key the file does not carry.
     */
    private static List<HoraireStandDto> horaires(List<HoraireStand> horaires) {
        return horaires.stream()
                .map(horaire -> new HoraireStandDto(
                        horaire.getMode(),
                        horaire.getJours(),
                        horaire.getJours() == dev.sylvain.planning.domain.TypeJoursHoraire.JOURS_SEMAINE
                                ? List.copyOf(horaire.getJoursSemaine())
                                : null,
                        horaire.getJours() == dev.sylvain.planning.domain.TypeJoursHoraire.PLAGE
                                ? horaire.getDateDebut()
                                : null,
                        horaire.getJours() == dev.sylvain.planning.domain.TypeJoursHoraire.PLAGE
                                ? horaire.getDateFin()
                                : null,
                        horaire.getJours() == dev.sylvain.planning.domain.TypeJoursHoraire.DATES
                                ? List.copyOf(horaire.getDates())
                                : null,
                        fenetres(horaire.getFenetres()),
                        horaire.getMotif()))
                .toList();
    }

    private static List<FenetreHoraireDto> fenetres(List<FenetreHoraire> fenetres) {
        return fenetres.stream()
                // heureFin absent rather than null: "jusqu'à la fermeture" reads
                // better as a missing end. Same for effectif: absent means
                // "inherit effectifMin", and writing it out would freeze
                // today's value into the file as if it had been chosen.
                .map(fenetre ->
                        new FenetreHoraireDto(fenetre.getHeureDebut(), fenetre.getHeureFin(), fenetre.getEffectif()))
                .toList();
    }

    private static List<EmplacementDto> emplacements(List<Emplacement> emplacements) {
        return emplacements.stream()
                .map(emplacement -> new EmplacementDto(
                        emplacement.getId(),
                        emplacement.getNom(),
                        emplacement.getLatitude(),
                        emplacement.getLongitude()))
                .toList();
    }

    private static List<TypologieDto> typologies(List<TypologieItem> typologies) {
        return typologies.stream()
                .map(typologie -> new TypologieDto(
                        typologie.id(),
                        typologie.label(),
                        typologie.ninja(),
                        typologie.maxCreneauxParAnimateur(),
                        typologie.description()))
                .toList();
    }

    /**
     * {@code null} when the edition tunes nothing — a rule left at the
     * catalogue's own state and its default weight is what a file without the
     * section already means, and writing it out would be noise. Sorted, so two
     * exports of the same edition are the same file.
     *
     * <p>The file lists the states the edition <b>chose</b>, in both
     * directions: the rules it switched off, and the rules it switched on
     * although the catalogue ships them off. A rule nobody touched appears in
     * neither list, so an edition that tuned nothing still writes no section —
     * and an import reads back the catalogue's own state for it.</p>
     */
    private static ContraintesDto contraintes(ScenarioYamlWriter.ScenarioExport export) {
        Map<String, Boolean> etats = export.etatsContraintes() == null ? Map.of() : export.etatsContraintes();
        Map<String, Integer> poids = export.poidsContraintes() == null ? Map.of() : export.poidsContraintes();
        Set<String> desactivees = etats.entrySet().stream()
                .filter(etat -> !etat.getValue())
                .map(Map.Entry::getKey)
                .collect(Collectors.toCollection(TreeSet::new));
        Set<String> activees = etats.entrySet().stream()
                .filter(Map.Entry::getValue)
                .map(Map.Entry::getKey)
                .collect(Collectors.toCollection(TreeSet::new));
        if (desactivees.isEmpty() && activees.isEmpty() && poids.isEmpty()) {
            return null;
        }
        return new ContraintesDto(
                desactivees.isEmpty() ? null : List.copyOf(desactivees),
                activees.isEmpty() ? null : List.copyOf(activees),
                poids.isEmpty() ? null : new TreeMap<>(poids));
    }

    /**
     * The créneau's database id is written back as the text id the
     * {@code creneaux:} section of the very same file uses, so the two halves
     * stay tied together whatever the ids become on import.
     *
     * <p>A constraint aiming at a créneau the export does not carry would be
     * refused on import: the scope is dropped rather than the constraint, which
     * then covers the whole event — the safe side for every prescriptive
     * type.</p>
     */
    private static List<ContrainteAdHocDto> contraintesAdHoc(
            List<ContrainteAdHoc> contraintes, List<Creneau> creneaux) {
        if (contraintes == null) {
            return null;
        }
        Set<Long> creneauxConnus = creneaux.stream().map(Creneau::getId).collect(Collectors.toSet());
        return contraintes.stream()
                .map(contrainte -> new ContrainteAdHocDto(
                        contrainte.getId(),
                        contrainte.getType(),
                        contrainte.getAnimateursConcernes().stream()
                                .filter(Objects::nonNull)
                                .map(Animateur::getId)
                                .toList(),
                        contrainte.getCreneau() != null
                                        && creneauxConnus.contains(
                                                contrainte.getCreneau().getId())
                                ? asString(contrainte.getCreneau().getId())
                                : null,
                        contrainte.getStand() == null
                                ? null
                                : contrainte.getStand().getId(),
                        contrainte.getRaison()))
                .toList();
    }

    /**
     * The five thresholds, written whole or not at all: read wholesale on the
     * way back in, a half-written section would say « no late hour » where the
     * edition simply had one.
     */
    private static ParametresQualiteDto parametresQualite(ParametresQualite parametres) {
        return parametres == null
                ? null
                : new ParametresQualiteDto(
                        parametres.maxEmplacementsDistinctsParJour(),
                        parametres.heureServiceTardif(),
                        parametres.heureServiceMatinal(),
                        parametres.reposSouhaiteApresServiceTardifMinutes(),
                        parametres.typologiesDistinctesMax());
    }

    private static ParametresSolveurDto parametresSolveur(ParametresSolveur parametres) {
        return parametres == null ? null : new ParametresSolveurDto(parametres.dureeResolutionSecondes());
    }

    /** Only the fields a scenario file is read back with. */
    private static ParametresLegauxDto parametresLegaux(ParametresLegaux parametres) {
        return parametres == null
                ? null
                : new ParametresLegauxDto(
                        parametres.getDureeHebdomadaireMaxMinutes(),
                        parametres.getPauseMinimaleEntreVacationsMinutes(),
                        parametres.getDureeVacationMaxMinutes(),
                        parametres.getReposQuotidienMinimalMinutes(),
                        parametres.isPauseSurPoste(),
                        parametres.getDureePauseMajeurMinutes(),
                        parametres.getDureePauseMineurMinutes(),
                        parametres.getCoupureRepasMinutes(),
                        parametres.getCoupureRepasMidiDebut(),
                        parametres.getCoupureRepasMidiFin(),
                        parametres.getCoupureRepasSoirDebut(),
                        parametres.getCoupureRepasSoirFin(),
                        parametres.getHeureDebutSoiree());
    }
}
