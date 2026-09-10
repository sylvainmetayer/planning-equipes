package dev.sylvain.planning.service.scenario;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.ContrainteAdHoc;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.Emplacement;
import dev.sylvain.planning.domain.FenetreHoraire;
import dev.sylvain.planning.domain.HoraireStand;
import dev.sylvain.planning.domain.IndisponibiliteStand;
import dev.sylvain.planning.domain.OuvertureStand;
import dev.sylvain.planning.domain.ParametresDecoupage;
import dev.sylvain.planning.domain.ParametresLegaux;
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
import dev.sylvain.planning.scenario.dto.OuvertureStandDto;
import dev.sylvain.planning.scenario.dto.ParametresDecoupageDto;
import dev.sylvain.planning.scenario.dto.ParametresLegauxDto;
import dev.sylvain.planning.scenario.dto.ParametresSolveurDto;
import dev.sylvain.planning.scenario.dto.PosteDto;
import dev.sylvain.planning.scenario.dto.ScenarioDto;
import dev.sylvain.planning.scenario.dto.StandDto;
import dev.sylvain.planning.scenario.dto.TypologieDto;
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
import dev.sylvain.planning.service.TypologieItem;

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

    private ScenarioDtoAssembler() {
    }

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
                parametresDecoupage(export.parametresDecoupage()),
                contraintes(export),
                nullWhenEmpty(typologies(export.typologies())),
                creneaux(creneaux),
                nullWhenEmpty(emplacements(export.emplacements())),
                stands(export.stands()),
                animateurs(export.animateurs()),
                // null, not empty: a scenario carrying decoupageAuto must not
                // pin a seat list, since the créneaux it would reference only
                // exist after the découpage has run on import.
                export.postes() == null ? null : postes(export.postes()),
                nullWhenEmpty(contraintesAdHoc(export.contraintesAdHoc(), creneaux)),
                null);
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
                .map(creneau -> new CreneauDto(asString(creneau.getId()), creneau.getJour(), creneau.getDate(),
                        creneau.getHeureDebut(), creneau.getHeureFin()))
                .toList();
    }

    private static List<StandDto> stands(List<Stand> stands) {
        return stands.stream()
                .map(stand -> new StandDto(
                        stand.getId(),
                        stand.getNom(),
                        stand.getEmplacement() == null ? null : stand.getEmplacement().getId(),
                        new ArrayList<>(stand.getTypologiesProposees()),
                        stand.getEffectifMin(),
                        stand.getEffectifMax(),
                        stand.isReserveMajeurs(),
                        stand.isPremium(),
                        stand.getNiveauEffort(),
                        stand.getFamille(),
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
                        animateur.getEmail() == null || animateur.getEmail().isBlank()
                                ? null : animateur.getEmail(),
                        animateur.getCompetences() == null ? Map.of()
                                : new LinkedHashMap<>(animateur.getCompetences()),
                        animateur.getJoursIndisponibles() == null ? List.of()
                                : animateur.getJoursIndisponibles().stream().sorted().toList(),
                        animateur.getSouhaits() == null ? List.of() : new ArrayList<>(animateur.getSouhaits())))
                .toList();
    }

    private static List<PosteDto> postes(List<PosteAffectation> postes) {
        return postes.stream()
                .map(poste -> new PosteDto(poste.getId(), poste.getStand().getId(),
                        asString(poste.getCreneau().getId()), null))
                .toList();
    }

    private static List<IndisponibiliteStandDto> indisponibilites(List<IndisponibiliteStand> indisponibilites) {
        return indisponibilites.stream()
                .map(indispo -> new IndisponibiliteStandDto(indispo.getDate(), indispo.getHeureDebut(),
                        indispo.getHeureFin(), indispo.getMotif()))
                .toList();
    }

    private static List<OuvertureStandDto> ouvertures(List<OuvertureStand> ouvertures) {
        return ouvertures.stream()
                .map(ouverture -> new OuvertureStandDto(ouverture.getDate(), ouverture.getHeureDebut(),
                        ouverture.getHeureFin(), ouverture.getMotif(), ouverture.getEffectif()))
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
                                ? List.copyOf(horaire.getJoursSemaine()) : null,
                        horaire.getJours() == dev.sylvain.planning.domain.TypeJoursHoraire.PLAGE
                                ? horaire.getDateDebut() : null,
                        horaire.getJours() == dev.sylvain.planning.domain.TypeJoursHoraire.PLAGE
                                ? horaire.getDateFin() : null,
                        horaire.getJours() == dev.sylvain.planning.domain.TypeJoursHoraire.DATES
                                ? List.copyOf(horaire.getDates()) : null,
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
                .map(fenetre -> new FenetreHoraireDto(fenetre.getHeureDebut(), fenetre.getHeureFin(),
                        fenetre.getEffectif()))
                .toList();
    }

    private static List<EmplacementDto> emplacements(List<Emplacement> emplacements) {
        return emplacements.stream()
                .map(emplacement -> new EmplacementDto(emplacement.getId(), emplacement.getNom(),
                        emplacement.getLatitude(), emplacement.getLongitude()))
                .toList();
    }

    private static List<TypologieDto> typologies(List<TypologieItem> typologies) {
        return typologies.stream()
                .map(typologie -> new TypologieDto(typologie.id(), typologie.label(), typologie.ninja()))
                .toList();
    }

    /**
     * {@code null} when the edition tunes nothing — every rule active at its
     * default weight is what a file without the section already means, and
     * writing it out would be noise. Sorted, so two exports of the same edition
     * are the same file.
     */
    private static ContraintesDto contraintes(ScenarioYamlWriter.ScenarioExport export) {
        Set<String> desactivees = export.contraintesDesactivees() == null ? Set.of() : export.contraintesDesactivees();
        Map<String, Integer> poids = export.poidsContraintes() == null ? Map.of() : export.poidsContraintes();
        if (desactivees.isEmpty() && poids.isEmpty()) {
            return null;
        }
        return new ContraintesDto(
                desactivees.isEmpty() ? null : List.copyOf(new TreeSet<>(desactivees)),
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
    private static List<ContrainteAdHocDto> contraintesAdHoc(List<ContrainteAdHoc> contraintes,
            List<Creneau> creneaux) {
        if (contraintes == null) {
            return null;
        }
        Set<Long> creneauxConnus = creneaux.stream().map(Creneau::getId).collect(Collectors.toSet());
        return contraintes.stream()
                .map(contrainte -> new ContrainteAdHocDto(
                        contrainte.getId(),
                        contrainte.getType(),
                        contrainte.getAnimateursConcernes().stream()
                                .filter(Objects::nonNull).map(Animateur::getId).toList(),
                        contrainte.getCreneau() != null && creneauxConnus.contains(contrainte.getCreneau().getId())
                                ? asString(contrainte.getCreneau().getId()) : null,
                        contrainte.getStand() == null ? null : contrainte.getStand().getId(),
                        contrainte.getRaison()))
                .toList();
    }

    private static ParametresSolveurDto parametresSolveur(ParametresSolveur parametres) {
        return parametres == null ? null : new ParametresSolveurDto(parametres.dureeResolutionSecondes());
    }

    /** Only the four fields a scenario file is read back with. */
    private static ParametresLegauxDto parametresLegaux(ParametresLegaux parametres) {
        return parametres == null ? null : new ParametresLegauxDto(
                parametres.getDureeHebdomadaireMaxMinutes(),
                parametres.getPauseMinimaleEntreVacationsMinutes(),
                parametres.getReposQuotidienMinimalMinutes(),
                parametres.isPauseSurPoste());
    }

    private static ParametresDecoupageDto parametresDecoupage(ParametresDecoupage parametres) {
        return parametres == null ? null : new ParametresDecoupageDto(
                parametres.getDureeVacationCibleMinutes(),
                parametres.getDureeVacationMinMinutes(),
                parametres.getDureeVacationMaxMinutes(),
                parametres.getDureeChevauchementMinutes(),
                parametres.getDureePauseRepasMinutes(),
                parametres.getFenetreRepasMidiDebut(),
                parametres.getFenetreRepasMidiFin(),
                parametres.getFenetreRepasSoirDebut(),
                parametres.getFenetreRepasSoirFin(),
                parametres.getStrategieCouverturePendantPause(),
                parametres.getNombreFamillesDecalage(),
                parametres.getDureeDecalageMaxMinutes(),
                parametres.getModeGrille());
    }
}
