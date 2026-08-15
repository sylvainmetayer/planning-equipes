package dev.sylvain.planning.service;

import java.text.Normalizer;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.ContrainteAdHoc;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.DecoupageAutoConfig;
import dev.sylvain.planning.domain.Emplacement;
import dev.sylvain.planning.domain.GroupeCreneau;
import dev.sylvain.planning.domain.IndisponibiliteStand;
import dev.sylvain.planning.domain.OuvertureStand;
import dev.sylvain.planning.domain.ParametresDecoupage;
import dev.sylvain.planning.domain.ParametresLegaux;
import dev.sylvain.planning.domain.ParametresSolveur;
import dev.sylvain.planning.domain.PlanningFestival;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.domain.VerrouillagePlanning;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotFoundException;

/**
 * Reference-data CRUD facade. Every mutation is written straight to PostgreSQL
 * through {@link ReferenceDataRepository}; there is no in-memory cache. Read and
 * write methods null-guard the repository so the non-CDI plain test (which
 * builds this service with {@code new}) stays green.
 */
@ApplicationScoped
public class ReferenceDataService {

    @Inject
    ReferenceDataRepository repository;

    @Inject
    ReferenceDataChangeTracker changeTracker;

    /** Kept for the non-CDI plain test which constructs and calls init() by hand. */
    void init() {
        // No-op: state lives in the database, seeded by Flyway migrations.
    }

    /* ------------------------------ Animateurs ----------------------------- */

    public List<Animateur> listAnimateurs() {
        return repository == null ? List.of() : repository.listAnimateurs();
    }

    public Animateur createAnimateur(Animateur animateur) {
        animateur.setId(requiredId(animateur.getId(), "animateur id"));
        validateCompetences(animateur);
        validateSouhaits(animateur);
        repository.saveAnimateur(animateur);
        markModified();
        return animateur;
    }

    public Animateur updateAnimateur(String id, Animateur animateur) {
        if (!repository.animateurExists(id)) {
            throw new NotFoundException("Animateur not found: " + id);
        }
        animateur.setId(id);
        validateCompetences(animateur);
        validateSouhaits(animateur);
        repository.saveAnimateur(animateur);
        markModified();
        return animateur;
    }

    /** Every competence key must reference an existing typologie — see {@link #validateTypologies(Stand)}. */
    private void validateCompetences(Animateur animateur) {
        if (animateur.getCompetences() == null) {
            return;
        }
        validateTypologieIds(animateur.getCompetences().keySet());
    }

    /** Every wished typologie must reference an existing typologie — see {@link #validateTypologies(Stand)}. */
    private void validateSouhaits(Animateur animateur) {
        if (animateur.getSouhaits() == null) {
            return;
        }
        validateTypologieIds(animateur.getSouhaits());
    }

    public void deleteAnimateur(String id) {
        repository.deleteAnimateur(id);
        markModified();
    }

    /* -------------------------------- Stands ------------------------------- */

    public List<Stand> listStands() {
        return repository == null ? List.of() : repository.listStands();
    }

    public Stand createStand(Stand stand) {
        stand.setId(requiredId(stand.getId(), "stand id"));
        validateEffectifs(stand);
        validateIndisponibilites(stand);
        validateOuvertures(stand);
        validateModesExclusifsParJour(stand);
        validateTypologies(stand);
        repository.saveStand(stand);
        markModified();
        return stand;
    }

    public Stand updateStand(String id, Stand stand) {
        if (!repository.standExists(id)) {
            throw new NotFoundException("Stand not found: " + id);
        }
        stand.setId(id);
        validateEffectifs(stand);
        validateIndisponibilites(stand);
        validateOuvertures(stand);
        validateModesExclusifsParJour(stand);
        validateTypologies(stand);
        repository.saveStand(stand);
        markModified();
        return stand;
    }

    /** Every proposed typologie must reference an id already present in the {@code typologie} referential. */
    private void validateTypologies(Stand stand) {
        if (stand.getTypologiesProposees() == null) {
            return;
        }
        validateTypologieIds(stand.getTypologiesProposees());
    }

    private void validateTypologieIds(Set<String> ids) {
        Set<String> inconnues = ids.stream()
                .filter(id -> !repository.typologieExists(id))
                .collect(Collectors.toCollection(TreeSet::new));
        if (!inconnues.isEmpty()) {
            throw new IllegalArgumentException(
                    "Typologie(s) inconnue(s) : " + inconnues + " — créez-les d'abord via /api/typologies");
        }
    }

    private void validateEffectifs(Stand stand) {
        if (stand.getEffectifMin() > stand.getEffectifMax()) {
            throw new IllegalArgumentException(
                    "effectifMin (" + stand.getEffectifMin() + ") cannot be greater than effectifMax ("
                            + stand.getEffectifMax() + ")");
        }
    }

    /** Every closure window must be a genuine, same-day interval — see {@code IndisponibiliteStand}. */
    private void validateIndisponibilites(Stand stand) {
        if (stand.getIndisponibilites() == null) {
            return;
        }
        for (IndisponibiliteStand indispo : stand.getIndisponibilites()) {
            if (indispo.getDate() == null || indispo.getHeureDebut() == null || indispo.getHeureFin() == null) {
                throw new IllegalArgumentException("Une indisponibilité de stand requiert une date, une heure de "
                        + "début et une heure de fin");
            }
            if (!indispo.getHeureFin().isAfter(indispo.getHeureDebut())) {
                throw new IllegalArgumentException(
                        "heureFin (" + indispo.getHeureFin() + ") doit être après heureDebut (" + indispo.getHeureDebut()
                                + ") — une indisponibilité ne peut pas chevaucher minuit, entrez-en deux");
            }
        }
    }

    /** Every opening window must be a genuine, same-day interval — see {@code OuvertureStand}. */
    private void validateOuvertures(Stand stand) {
        if (stand.getOuvertures() == null) {
            return;
        }
        for (OuvertureStand ouverture : stand.getOuvertures()) {
            if (ouverture.getDate() == null || ouverture.getHeureDebut() == null || ouverture.getHeureFin() == null) {
                throw new IllegalArgumentException(
                        "Une ouverture de stand requiert une date, une heure de début et une heure de fin");
            }
            if (!ouverture.getHeureFin().isAfter(ouverture.getHeureDebut())) {
                throw new IllegalArgumentException(
                        "heureFin (" + ouverture.getHeureFin() + ") doit être après heureDebut ("
                                + ouverture.getHeureDebut() + ") — une ouverture ne peut pas chevaucher minuit, "
                                + "entrez-en deux");
            }
        }
    }

    /**
     * A day can never carry both a closure and an opening window: mixing the
     * two modes for one day is ambiguous (which one does the solver honour?),
     * so it is rejected here rather than silently picking one — see
     * {@code OuvertureStand}'s javadoc for the three-state rule this protects.
     */
    private void validateModesExclusifsParJour(Stand stand) {
        Set<LocalDate> joursFermeture = stand.getIndisponibilites().stream()
                .map(IndisponibiliteStand::getDate)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Set<LocalDate> joursOuverture = stand.getOuvertures().stream()
                .map(OuvertureStand::getDate)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Set<LocalDate> conflits = new TreeSet<>(joursFermeture);
        conflits.retainAll(joursOuverture);
        if (!conflits.isEmpty()) {
            throw new IllegalArgumentException("Un jour ne peut pas avoir à la fois une fermeture et une ouverture "
                    + "pour le stand " + stand.getId() + " : " + conflits);
        }
    }

    public void deleteStand(String id) {
        repository.deleteStand(id);
        markModified();
    }

    /* ----------------------------- Emplacements ----------------------------- */

    public List<Emplacement> listEmplacements() {
        return repository == null ? List.of() : repository.listEmplacements();
    }

    public Emplacement createEmplacement(Emplacement emplacement) {
        emplacement.setId(requiredId(emplacement.getId(), "emplacement id"));
        validateCoordonnees(emplacement);
        repository.saveEmplacement(emplacement);
        markModified();
        return emplacement;
    }

    public Emplacement updateEmplacement(String id, Emplacement emplacement) {
        if (!repository.emplacementExists(id)) {
            throw new NotFoundException("Emplacement not found: " + id);
        }
        emplacement.setId(id);
        validateCoordonnees(emplacement);
        repository.saveEmplacement(emplacement);
        markModified();
        return emplacement;
    }

    public void deleteEmplacement(String id) {
        repository.deleteEmplacement(id);
        markModified();
    }

    private void validateCoordonnees(Emplacement emplacement) {
        Double latitude = emplacement.getLatitude();
        Double longitude = emplacement.getLongitude();
        if (latitude != null && (latitude < -90 || latitude > 90)) {
            throw new IllegalArgumentException("latitude must be between -90 and 90");
        }
        if (longitude != null && (longitude < -180 || longitude > 180)) {
            throw new IllegalArgumentException("longitude must be between -180 and 180");
        }
    }

    /* ------------------------------ Timeslots ------------------------------ */

    private static final String GROUPE_CRENEAU_DEFAUT_ID = "DEFAUT";

    public List<Creneau> listCreneaux() {
        return repository == null ? List.of() : repository.listCreneaux();
    }

    /** Timeslots of the currently active group only — what the solver builds its problem from. */
    public List<Creneau> listCreneauxGroupeActif() {
        return repository == null ? List.of() : repository.listCreneauxGroupeActif();
    }

    public Creneau createCreneau(Creneau creneau) {
        creneau.setId(null); // ignore any client-supplied id — the database always generates it
        defaultGroupeIfMissing(creneau);
        Creneau created = repository.insertCreneau(creneau);
        markModified();
        return created;
    }

    public Creneau updateCreneau(Long id, Creneau creneau) {
        if (!repository.creneauExists(id)) {
            throw new NotFoundException("Timeslot not found: " + id);
        }
        creneau.setId(id);
        defaultGroupeIfMissing(creneau);
        repository.updateCreneau(creneau);
        markModified();
        return creneau;
    }

    public void deleteCreneau(Long id) {
        repository.deleteCreneau(id);
        markModified();
    }

    /** Clients that don't send a group (older callers, tests) land in the default one. */
    private void defaultGroupeIfMissing(Creneau creneau) {
        if (creneau.getGroupe() == null || creneau.getGroupe().getId() == null) {
            creneau.setGroupe(new GroupeCreneau(GROUPE_CRENEAU_DEFAUT_ID, null, false));
        }
    }

    /* -------------------------- Timeslot groups ----------------------------- */

    public List<GroupeCreneau> listGroupesCreneaux() {
        return repository == null ? List.of() : repository.listGroupesCreneaux();
    }

    public GroupeCreneau createGroupeCreneau(GroupeCreneau groupe) {
        groupe.setId(requiredId(groupe.getId(), "timeslot group id"));
        if (groupe.getNom() == null || groupe.getNom().isBlank()) {
            throw new IllegalArgumentException("timeslot group name is required");
        }
        groupe.setActif(false);
        repository.saveGroupeCreneau(groupe);
        markModified();
        return groupe;
    }

    public GroupeCreneau updateGroupeCreneau(String id, GroupeCreneau groupe) {
        if (!repository.groupeCreneauExists(id)) {
            throw new NotFoundException("Timeslot group not found: " + id);
        }
        if (groupe.getNom() == null || groupe.getNom().isBlank()) {
            throw new IllegalArgumentException("timeslot group name is required");
        }
        groupe.setId(id);
        repository.saveGroupeCreneau(groupe);
        markModified();
        return groupe;
    }

    /**
     * Doesn't mark reference data as modified: switching the active group is
     * already surfaced precisely by the groupe de créneaux mismatch check (which
     * group the last solve ran for vs. the active one), so flagging it here too
     * would just be a redundant, less specific warning.
     */
    public void activerGroupeCreneau(String id) {
        if (!repository.groupeCreneauExists(id)) {
            throw new NotFoundException("Timeslot group not found: " + id);
        }
        repository.activerGroupeCreneau(id);
    }

    public void deleteGroupeCreneau(String id) {
        boolean actif = repository.listGroupesCreneaux().stream()
                .anyMatch(groupe -> groupe.getId().equals(id) && groupe.isActif());
        if (actif) {
            throw new IllegalArgumentException("Impossible de supprimer le groupe actif");
        }
        repository.deleteGroupeCreneau(id);
        markModified();
    }

    /* ------------------------------ Découpage ------------------------------- */

    /**
     * Generates the vacations a source "amplitudes" group would produce,
     * without persisting anything — used by the découpage preview screen.
     */
    public List<Creneau> previsualiserDecoupage(String groupeSourceId) {
        List<Creneau> amplitudes = repository.listCreneauxParGroupe(groupeSourceId);
        if (amplitudes.isEmpty()) {
            throw new IllegalArgumentException("Le groupe source ne contient aucune amplitude: " + groupeSourceId);
        }
        return VacationGeneratorService.genererVacations(amplitudes, getParametresDecoupage());
    }

    /**
     * Materializes the vacations generated from {@code groupeSourceId}'s
     * amplitudes into {@code groupeCibleId} (created with {@code nomGroupeCible}
     * if it doesn't exist yet), replacing that target group's créneaux
     * entirely — every other group, including the source, is untouched.
     */
    public GroupeCreneau genererDecoupage(String groupeSourceId, String groupeCibleId, String nomGroupeCible,
            boolean activerGroupeCible) {
        if (!repository.groupeCreneauExists(groupeSourceId)) {
            throw new NotFoundException("Timeslot group not found: " + groupeSourceId);
        }
        List<Creneau> vacations = previsualiserDecoupage(groupeSourceId);

        GroupeCreneau cible = repository.listGroupesCreneaux().stream()
                .filter(g -> g.getId().equals(groupeCibleId))
                .findFirst()
                .orElseGet(() -> new GroupeCreneau(requiredId(groupeCibleId, "target timeslot group id"),
                        requiredId(nomGroupeCible, "target timeslot group name"), false));
        cible.setGroupeSourceId(groupeSourceId);
        repository.saveGroupeCreneau(cible);
        repository.replaceCreneauxDuGroupe(cible.getId(), vacations);
        markModified();
        if (activerGroupeCible) {
            repository.activerGroupeCreneau(cible.getId());
        }
        return cible;
    }

    /**
     * Applies a scenario's optional {@code decoupageAuto:} section right after
     * its raw reference data is imported: lands the scenario's créneaux
     * (amplitudes) into a source group named {@link DecoupageAutoConfig#groupeSourceNom()}
     * (created if needed), runs the day-to-vacations découpage against it, and
     * activates the resulting target group named
     * {@link DecoupageAutoConfig#groupeCibleNom()} — sparing the operator the
     * manual "Découpage" screen round-trip after every import of that
     * scenario. Both group ids are derived from their name the same way the
     * découpage screen derives one from a free-typed name (see the frontend's
     * {@code slugify}), reusing an existing group of that name if one already
     * exists instead of creating a duplicate.
     */
    public GroupeCreneau appliquerDecoupageAutomatique(PlanningFestival planning, DecoupageAutoConfig config) {
        GroupeCreneau source = assurerGroupeCreneauParNom(config.groupeSourceNom());
        activerGroupeCreneau(source.getId());
        importFromPlanning(planning);
        String groupeCibleId = resolveGroupeIdParNom(config.groupeCibleNom());
        return genererDecoupage(source.getId(), groupeCibleId, config.groupeCibleNom(), true);
    }

    /** Finds a timeslot group by name (case-insensitive), or creates one otherwise. */
    private GroupeCreneau assurerGroupeCreneauParNom(String nom) {
        List<GroupeCreneau> existants = listGroupesCreneaux();
        return existants.stream()
                .filter(groupe -> nom.equalsIgnoreCase(groupe.getNom()))
                .findFirst()
                .orElseGet(() -> createGroupeCreneau(new GroupeCreneau(slugifyGroupeId(nom, existants), nom, false)));
    }

    /** Resolves the id a timeslot group named {@code nom} already has, or the id it would get if created. */
    private String resolveGroupeIdParNom(String nom) {
        List<GroupeCreneau> existants = listGroupesCreneaux();
        return existants.stream()
                .filter(groupe -> nom.equalsIgnoreCase(groupe.getNom()))
                .map(GroupeCreneau::getId)
                .findFirst()
                .orElseGet(() -> slugifyGroupeId(nom, existants));
    }

    /**
     * Derives a stable id from a free-typed name — accents stripped,
     * uppercased, non-alphanumeric runs collapsed to a dash — suffixed with
     * {@code -2}, {@code -3}... until it doesn't collide with an existing
     * group id. Mirrors the frontend's {@code slugify} (see {@code slug.ts}),
     * used there for the same purpose on the découpage screen.
     */
    private static String slugifyGroupeId(String nom, List<GroupeCreneau> groupesExistants) {
        String sansAccents = Normalizer.normalize(nom, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        String base = sansAccents.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (base.isBlank()) {
            base = "GROUPE";
        }
        Set<String> idsExistants = groupesExistants.stream().map(GroupeCreneau::getId)
                .collect(Collectors.toCollection(HashSet::new));
        String id = base;
        for (int suffixe = 2; idsExistants.contains(id); suffixe++) {
            id = base + "-" + suffixe;
        }
        return id;
    }

    /* ------------------------------ Typologies ----------------------------- */

    public List<TypologieItem> listTypologies() {
        return repository == null ? List.of() : repository.listTypologies();
    }

    public TypologieItem createTypologie(TypologieItem typologie) {
        String id = requiredId(typologie.id(), "typology id");
        TypologieItem created = new TypologieItem(id, typologie.label(), typologie.ninja());
        repository.saveTypologie(created);
        markModified();
        return created;
    }

    public TypologieItem updateTypologie(String id, TypologieItem typologie) {
        if (!repository.typologieExists(id)) {
            throw new NotFoundException("Typology not found: " + id);
        }
        TypologieItem updated = new TypologieItem(id, typologie.label(), typologie.ninja());
        repository.saveTypologie(updated);
        markModified();
        return updated;
    }

    /**
     * Id of the typologie flagged ninja, if any. Animateurs holding it are the
     * versatile profiles the solver may dispatch on any stand.
     */
    public Optional<String> typologieNinja() {
        return repository == null ? Optional.empty() : repository.findTypologieNinja();
    }

    public void deleteTypologie(String id) {
        if (repository.typologieEnUsage(id)) {
            throw new IllegalArgumentException(
                    "Typologie " + id + " utilisée par au moins un stand ou animateur — retirez-la d'abord");
        }
        repository.deleteTypologie(id);
        markModified();
    }

    /* --------------------------- Ad hoc constraints ------------------------ */

    public List<ContrainteAdHoc> listContraintesAdHoc() {
        return repository == null ? List.of() : repository.listContraintes();
    }

    public ContrainteAdHoc createContrainteAdHoc(ContrainteAdHoc contrainte) {
        contrainte.setId(requiredId(contrainte.getId(), "constraint id"));
        if (contrainte.getCreeLe() == null) {
            contrainte.setCreeLe(Instant.now());
        }
        repository.saveContrainte(contrainte);
        markModified();
        return contrainte;
    }

    public void deleteContrainteAdHoc(String id) {
        repository.deleteContrainte(id);
        markModified();
    }

    public List<ContrainteAdHoc> snapshotContraintes() {
        return repository == null ? List.of() : repository.listContraintes();
    }

    /* --------------------------- Planning locks ----------------------------- */

    public List<VerrouillagePlanning> listVerrouillages() {
        return repository == null ? List.of() : repository.listVerrouillages();
    }

    /**
     * The locks a solve must apply: those recorded for the groupe de créneaux
     * currently active. Locks kept for another group stay in the table, dormant
     * until that group is activated again.
     */
    public List<VerrouillagePlanning> snapshotVerrouillagesGroupeActif() {
        if (repository == null) {
            return List.of();
        }
        return repository.listVerrouillagesGroupe(repository.groupeCreneauActifId());
    }

    /**
     * Records a lock, defaulting its group to the active one and rejecting a
     * target that does not match the type or does not exist. Locking an already
     * locked target is a no-op, not an error.
     */
    public VerrouillagePlanning createVerrouillage(VerrouillagePlanning verrouillage) {
        if (verrouillage.getType() == null) {
            throw new IllegalArgumentException("Type de verrouillage manquant");
        }
        normaliserCible(verrouillage);
        if (verrouillage.getGroupeCreneauId() == null || verrouillage.getGroupeCreneauId().isBlank()) {
            verrouillage.setGroupeCreneauId(repository.groupeCreneauActifId());
        }
        if (verrouillage.getId() == null || verrouillage.getId().isBlank()) {
            verrouillage.setId(UUID.randomUUID().toString());
        }
        if (verrouillage.getCreeLe() == null) {
            verrouillage.setCreeLe(Instant.now());
        }
        repository.saveVerrouillage(verrouillage);
        markModified();
        return verrouillage;
    }

    /**
     * Keeps only the target column the type expects — a payload carrying two
     * targets would be ambiguous, and the check constraint would reject it with
     * a raw SQL error instead of a usable message.
     */
    private void normaliserCible(VerrouillagePlanning verrouillage) {
        switch (verrouillage.getType()) {
            case ANIMATEUR -> {
                String animateurId = requiredId(verrouillage.getAnimateurId(), "animateur id");
                if (!repository.animateurExists(animateurId)) {
                    throw new IllegalArgumentException("Animateur inconnu : " + animateurId);
                }
                verrouillage.setStandId(null);
                verrouillage.setCreneauId(null);
                verrouillage.setJour(null);
            }
            case STAND -> {
                String standId = requiredId(verrouillage.getStandId(), "stand id");
                if (!repository.standExists(standId)) {
                    throw new IllegalArgumentException("Stand inconnu : " + standId);
                }
                verrouillage.setAnimateurId(null);
                verrouillage.setCreneauId(null);
                verrouillage.setJour(null);
            }
            case CRENEAU -> {
                Long creneauId = verrouillage.getCreneauId();
                if (creneauId == null) {
                    throw new IllegalArgumentException("Missing créneau id");
                }
                if (!repository.creneauExists(creneauId)) {
                    throw new IllegalArgumentException("Créneau inconnu : " + creneauId);
                }
                verrouillage.setAnimateurId(null);
                verrouillage.setStandId(null);
                verrouillage.setJour(null);
            }
            case JOUR -> {
                if (verrouillage.getJour() == null) {
                    throw new IllegalArgumentException("Missing jour");
                }
                verrouillage.setAnimateurId(null);
                verrouillage.setStandId(null);
                verrouillage.setCreneauId(null);
            }
        }
    }

    public void deleteVerrouillage(String id) {
        repository.deleteVerrouillage(id);
        markModified();
    }

    /**
     * Replaces the whole persisted reference dataset with the one carried by a
     * (sample or solved) planning, so it becomes editable through the CRUD
     * endpoints. Delegated to the repository in a single transaction.
     */
    public void importFromPlanning(PlanningFestival planning) {
        if (repository != null) {
            repository.importFromPlanning(planning);
            markModified();
        }
    }

    /* --------------------------- Legal parameters --------------------------- */

    public ParametresLegaux getParametresLegaux() {
        return repository == null ? new ParametresLegaux() : repository.getParametresLegaux();
    }

    /**
     * Saves the legal parameters, refusing anything above the ordre public
     * ceilings.
     *
     * <p>Art. <b>L3121-20</b> (48 h/week) is a disposition d'ordre public: no
     * agreement and no configuration may exceed it, short of an exceptional
     * administrative authorisation the application knows nothing about. Art.
     * <b>L3162-1</b> caps young workers at 35 h. A default value that is
     * correct protects nothing if the entry screen does not; before this check,
     * an administrator could store 100 h/week and the solver would happily
     * report a "valid" plan (hard score zero) that is plainly illegal.</p>
     *
     * <p>A <i>lower</i> value stays free: it is more protective than the law.</p>
     */
    public ParametresLegaux updateParametresLegaux(ParametresLegaux parametres) {
        verifierPlafond(parametres.getDureeHebdomadaireMaxMinutes(),
                ParametresLegaux.DUREE_HEBDOMADAIRE_MAX_MINUTES_PAR_DEFAUT,
                "dureeHebdomadaireMaxMinutes",
                "la durée hebdomadaire maximale des majeurs ne peut pas dépasser 48 h "
                        + "(Code du travail art. L3121-20, disposition d'ordre public)");
        verifierPlafond(parametres.getDureeHebdomadaireMaxMineurMinutes(),
                ParametresLegaux.DUREE_HEBDOMADAIRE_MAX_MINEUR_MINUTES_PAR_DEFAUT,
                "dureeHebdomadaireMaxMineurMinutes",
                "la durée hebdomadaire maximale des mineurs ne peut pas dépasser 35 h "
                        + "(Code du travail art. L3162-1)");
        if (parametres.getPauseMinimaleEntreVacationsMinutes() < 0) {
            throw new IllegalArgumentException("pauseMinimaleEntreVacationsMinutes must not be negative");
        }
        if (parametres.getReposQuotidienMinimalMinutes() < 0) {
            throw new IllegalArgumentException("reposQuotidienMinimalMinutes must not be negative");
        }
        repository.saveParametresLegaux(parametres);
        markModified();
        return parametres;
    }

    private static void verifierPlafond(int valeurMinutes, int plafondMinutes, String champ, String message) {
        if (valeurMinutes <= 0) {
            throw new IllegalArgumentException(champ + " must be positive");
        }
        if (valeurMinutes > plafondMinutes) {
            throw new IllegalArgumentException(message);
        }
    }

    /* --------------------------- Découpage parameters ------------------------ */

    public ParametresDecoupage getParametresDecoupage() {
        return repository == null ? new ParametresDecoupage() : repository.getParametresDecoupage();
    }

    public ParametresDecoupage updateParametresDecoupage(ParametresDecoupage parametres) {
        if (parametres.getDureeVacationMinMinutes() <= 0 || parametres.getDureeVacationMaxMinutes() <= 0
                || parametres.getDureeVacationCibleMinutes() <= 0) {
            throw new IllegalArgumentException("vacation durations must be positive");
        }
        if (parametres.getDureeVacationMinMinutes() > parametres.getDureeVacationMaxMinutes()) {
            throw new IllegalArgumentException(
                    "dureeVacationMinMinutes cannot be greater than dureeVacationMaxMinutes");
        }
        if (parametres.getDureeChevauchementMinutes() < 0 || parametres.getDureePauseRepasMinutes() < 0) {
            throw new IllegalArgumentException("overlap and meal-break durations must not be negative");
        }
        repository.saveParametresDecoupage(parametres);
        markModified();
        return parametres;
    }

    /* --------------------------- Solver parameters ---------------------------- */

    public ParametresSolveur getParametresSolveur() {
        return repository == null ? new ParametresSolveur() : repository.getParametresSolveur();
    }

    /**
     * Saves the solver's default termination duration (Données tab). Not a
     * problem fact and not tracked by {@link ReferenceDataChangeTracker}: it
     * only changes how long a solve/analyze runs, not the reference data fed
     * to it.
     */
    public ParametresSolveur updateParametresSolveur(ParametresSolveur parametres) {
        if (parametres.getDureeResolutionSecondes() <= 0) {
            throw new IllegalArgumentException("dureeResolutionSecondes must be positive");
        }
        repository.saveParametresSolveur(parametres);
        return parametres;
    }

    /* --------------------------- Constraint toggles -------------------------- */

    public java.util.Set<String> getContraintesDesactivees() {
        return repository == null ? java.util.Set.of() : repository.getContraintesDesactivees();
    }

    /**
     * Enables or disables a constraint for the next solve, recording the
     * reason and the author when it is being <b>disabled</b>.
     *
     * <p>Disabling a hard legal constraint lets the solver return a plan with a
     * hard score of zero that nonetheless breaks the Code du travail, so the
     * decision must leave a trace (constat C2 of the RH compliance audit).
     * <b>Known limit</b>: the application has no authentication, so
     * {@code utilisateurId} is whatever the client claims — exactly like
     * {@code ContrainteAdHoc.creeParUtilisateurId}. The reason and the
     * timestamp are real; the author is not proof of accountability.</p>
     */
    public void setContrainteActive(String nom, boolean actif, String motif, String utilisateurId) {
        if (repository != null) {
            repository.setContrainteActive(nom, actif, motif, utilisateurId);
            markModified();
        }
    }

    private String requiredId(String id, String fieldName) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Missing " + fieldName);
        }
        return id;
    }

    private void markModified() {
        if (changeTracker != null) {
            changeTracker.markModified();
        }
    }

    /**
     * A referential typologie. {@code ninja} marks the single typologie whose
     * holders are considered versatile: they are eligible for any stand and are
     * the pool the "buffer de polyvalents" soft constraint keeps some slack on.
     * At most one typologie of the referential carries the flag — the service
     * clears the previous one on save, and a partial unique index (V30) backs
     * the rule up in the database.
     */
    public record TypologieItem(String id, String label, boolean ninja) {
        public TypologieItem {
            if (label == null || label.isBlank()) {
                label = id;
            }
        }

        public TypologieItem(String id, String label) {
            this(id, label, false);
        }
    }
}
