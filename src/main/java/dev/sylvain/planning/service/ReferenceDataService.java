package dev.sylvain.planning.service;

import java.text.Normalizer;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
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
import dev.sylvain.planning.domain.Emplacement;
import dev.sylvain.planning.domain.FenetreHoraire;
import dev.sylvain.planning.domain.HoraireStand;
import dev.sylvain.planning.domain.IndisponibiliteStand;
import dev.sylvain.planning.domain.ModeHoraire;
import dev.sylvain.planning.domain.OuvertureStand;
import dev.sylvain.planning.domain.ParametresDecoupage;
import dev.sylvain.planning.domain.ParametresLegaux;
import dev.sylvain.planning.domain.ParametresSolveur;
import dev.sylvain.planning.domain.PlanningFestival;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.domain.TypeContrainteAdHoc;
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

    /**
     * Stands as entered: the recurring {@link HoraireStand} rules and the dated
     * exceptions, side by side, with no expansion. This is the CRUD view — what
     * the admin UI edits and what a save writes back. Anything that needs the
     * <em>effective</em> windows of a given day wants
     * {@link #listStandsResolus()} instead.
     */
    public List<Stand> listStands() {
        return repository == null ? List.of() : repository.listStands();
    }

    /**
     * Stands with their rules already expanded against the active timeslot
     * group's days, so {@link Creneau#segmentsOuvertsMinutes(Stand)} sees the
     * effective windows — what the solver, the poste generation and the
     * feasibility analysis all build on.
     *
     * <p>The expansion lands on {@link Stand#setFenetresEffectives} and never on
     * the persisted lists, so these instances stay safe to hand to a save path
     * (see {@link HoraireStandResolver}).</p>
     */
    public List<Stand> listStandsResolus() {
        List<Stand> stands = listStands();
        HoraireStandResolver.appliquer(stands, listCreneaux());
        return stands;
    }

    public Stand createStand(Stand stand) {
        stand.setId(requiredId(stand.getId(), "stand id"));
        validateStand(stand);
        repository.saveStand(stand);
        markModified();
        return stand;
    }

    public Stand updateStand(String id, Stand stand) {
        if (!repository.standExists(id)) {
            throw new NotFoundException("Stand not found: " + id);
        }
        stand.setId(id);
        validateStand(stand);
        repository.saveStand(stand);
        markModified();
        return stand;
    }

    private void validateStand(Stand stand) {
        validateEffectifs(stand);
        validateIndisponibilites(stand);
        validateOuvertures(stand);
        validateModesExclusifsParJour(stand);
        validateHoraires(stand);
        validateTypologies(stand);
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

    /**
     * Every closure window must be a genuine, same-day interval — see
     * {@code IndisponibiliteStand}. A {@code null} {@code heureFin} is
     * accepted and means "until closing time".
     */
    private void validateIndisponibilites(Stand stand) {
        if (stand.getIndisponibilites() == null) {
            return;
        }
        for (IndisponibiliteStand indispo : stand.getIndisponibilites()) {
            if (indispo.getDate() == null || indispo.getHeureDebut() == null) {
                throw new IllegalArgumentException("Une indisponibilité de stand requiert une date et une heure de "
                        + "début (l'heure de fin peut être vide : jusqu'à la fermeture)");
            }
            if (indispo.getHeureFin() != null && !indispo.getHeureFin().isAfter(indispo.getHeureDebut())) {
                throw new IllegalArgumentException(
                        "heureFin (" + indispo.getHeureFin() + ") doit être après heureDebut (" + indispo.getHeureDebut()
                                + ") — une indisponibilité ne peut pas chevaucher minuit, entrez-en deux");
            }
        }
    }

    /**
     * Every opening window must be a genuine, same-day interval — see
     * {@code OuvertureStand}. A {@code null} {@code heureFin} is accepted and
     * means "until closing time".
     */
    private void validateOuvertures(Stand stand) {
        if (stand.getOuvertures() == null) {
            return;
        }
        for (OuvertureStand ouverture : stand.getOuvertures()) {
            if (ouverture.getDate() == null || ouverture.getHeureDebut() == null) {
                throw new IllegalArgumentException("Une ouverture de stand requiert une date et une heure de début "
                        + "(l'heure de fin peut être vide : jusqu'à la fermeture)");
            }
            if (ouverture.getHeureFin() != null && !ouverture.getHeureFin().isAfter(ouverture.getHeureDebut())) {
                throw new IllegalArgumentException(
                        "heureFin (" + ouverture.getHeureFin() + ") doit être après heureDebut ("
                                + ouverture.getHeureDebut() + ") — une ouverture ne peut pas chevaucher minuit, "
                                + "entrez-en deux");
            }
        }
    }

    /**
     * Recurring rules must each be self-consistent (a selector with the data it
     * needs, at least one usable window), and the set of them must not leave the
     * resolver an arbitrary choice to make.
     *
     * <p>That second part is the interesting one: two rules of the <b>same</b>
     * day selector, whose day sets intersect, but with opposite
     * {@link ModeHoraire}, would give a day both "closed except…" and "open
     * only…" at the same specificity. There is no non-arbitrary winner, so it is
     * rejected here — exactly as {@link #validateModesExclusifsParJour} does for
     * the dated exceptions. Two rules of <i>different</i> specificity are fine
     * and expected ("open 14:00→closing every day, closed all day on the 14th"):
     * the more specific one simply wins.</p>
     */
    private void validateHoraires(Stand stand) {
        List<HoraireStand> horaires = stand.getHoraires();
        if (horaires == null || horaires.isEmpty()) {
            return;
        }
        for (HoraireStand horaire : horaires) {
            validateHoraire(horaire);
        }
        for (int i = 0; i < horaires.size(); i++) {
            for (int j = i + 1; j < horaires.size(); j++) {
                HoraireStand a = horaires.get(i);
                HoraireStand b = horaires.get(j);
                if (a.getMode() != b.getMode() && a.joursSeChevauchentAvec(b)) {
                    throw new IllegalArgumentException("Deux horaires de même portée (" + a.getJours()
                            + ") portant sur les mêmes jours ne peuvent pas être l'un une ouverture et l'autre une "
                            + "fermeture pour le stand " + stand.getId()
                            + " — utilisez une portée plus précise pour celui qui doit primer");
                }
            }
        }
    }

    private void validateHoraire(HoraireStand horaire) {
        if (horaire.getFenetres().isEmpty()) {
            throw new IllegalArgumentException("Un horaire de stand requiert au moins une fenêtre horaire");
        }
        for (FenetreHoraire fenetre : horaire.getFenetres()) {
            if (fenetre.getHeureDebut() == null) {
                throw new IllegalArgumentException("Une fenêtre horaire requiert une heure de début "
                        + "(l'heure de fin peut être vide : jusqu'à la fermeture)");
            }
            if (fenetre.getHeureFin() != null && !fenetre.getHeureFin().isAfter(fenetre.getHeureDebut())) {
                throw new IllegalArgumentException("heureFin (" + fenetre.getHeureFin() + ") doit être après heureDebut ("
                        + fenetre.getHeureDebut() + ") — une fenêtre horaire ne peut pas chevaucher minuit, "
                        + "entrez-en deux");
            }
        }
        switch (horaire.getJours()) {
            case JOURS_SEMAINE -> {
                if (horaire.getJoursSemaine().isEmpty()) {
                    throw new IllegalArgumentException(
                            "Un horaire de portée JOURS_SEMAINE requiert au moins un jour de la semaine");
                }
            }
            case PLAGE -> {
                if (horaire.getDateDebut() == null || horaire.getDateFin() == null) {
                    throw new IllegalArgumentException(
                            "Un horaire de portée PLAGE requiert une dateDebut et une dateFin");
                }
                if (horaire.getDateFin().isBefore(horaire.getDateDebut())) {
                    throw new IllegalArgumentException("dateFin (" + horaire.getDateFin() + ") doit être après ou égale "
                            + "à dateDebut (" + horaire.getDateDebut() + ")");
                }
            }
            case DATES -> {
                if (horaire.getDates().isEmpty()) {
                    throw new IllegalArgumentException("Un horaire de portée DATES requiert au moins une date");
                }
            }
            case TOUS -> {
                // Nothing else to check: the selector carries no data of its own.
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

    /**
     * Rewrites every stand's hand-entered dated windows as the recurring
     * horaires they repeat, against the edition's days. With
     * {@code appliquer} false nothing is written: the returned report describes
     * what the operation <em>would</em> do, which is what makes it safe to show
     * before committing to it.
     *
     * <p>Only stands the compaction proved equivalent are saved
     * ({@link CompactageHoraires#ecartMaximalMinutes}); the others come back in
     * the report with the reason they were left alone. Each one is saved
     * individually so a single problematic stand cannot roll back the rest.</p>
     */
    public CompactageHoraires.RapportCompactage compacterHoraires(boolean appliquer) {
        List<Stand> stands = listStands();
        List<Creneau> creneaux = listCreneaux();
        CompactageHoraires.RapportCompactage rapport = CompactageHoraires.compacter(stands, creneaux, appliquer);
        if (!appliquer) {
            return rapport;
        }
        Set<String> compactes = rapport.stands().stream()
                .filter(CompactageHoraires.LigneCompactage::compacte)
                .map(CompactageHoraires.LigneCompactage::standId)
                .collect(Collectors.toSet());
        boolean modifie = false;
        for (Stand stand : stands) {
            if (compactes.contains(stand.getId())) {
                repository.saveStand(stand);
                modifie = true;
            }
        }
        if (modifie) {
            markModified();
        }
        return rapport;
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

    public List<Creneau> listCreneaux() {
        return repository == null ? List.of() : repository.listCreneaux();
    }

    public Creneau createCreneau(Creneau creneau) {
        creneau.setId(null); // ignore any client-supplied id — the database always generates it
        Creneau created = repository.insertCreneau(creneau);
        markModified();
        return created;
    }

    public Creneau updateCreneau(Long id, Creneau creneau) {
        if (!repository.creneauExists(id)) {
            throw new NotFoundException("Timeslot not found: " + id);
        }
        creneau.setId(id);
        repository.updateCreneau(creneau);
        markModified();
        return creneau;
    }

    public void deleteCreneau(Long id) {
        repository.deleteCreneau(id);
        markModified();
    }

    /**
     * Inserts a batch of créneaux — the product of one recurrence rule — as a
     * single reference-data change.
     *
     * <p>Not a loop over {@link #createCreneau} at the caller's level on
     * purpose: that would stamp the "données modifiées depuis le dernier
     * solve" marker once per row, so a rule covering fourteen days would look
     * like fourteen separate edits in the toolbar warnings. One rule is one
     * edit.</p>
     *
     * <p>Day numbers are deliberately not touched here: {@link Creneau#getJour()}
     * is never persisted, it is recomputed on read by
     * {@link Creneau#assignerJours} over the whole edition — which is also
     * what keeps the numbering correct when a batch adds a date earlier than
     * every existing one.</p>
     */
    public List<Creneau> createCreneaux(List<Creneau> creneaux) {
        List<Creneau> crees = new ArrayList<>();
        for (Creneau creneau : creneaux) {
            creneau.setId(null); // ignore any client-supplied id — the database always generates it
            crees.add(repository.insertCreneau(creneau));
        }
        if (!crees.isEmpty()) {
            markModified();
        }
        return crees;
    }

    /** Deletes a batch of créneaux, for the same "one intent, one edit" reason as {@link #createCreneaux}. */
    public int deleteCreneaux(Collection<Long> ids) {
        int supprimes = 0;
        for (Long id : ids) {
            repository.deleteCreneau(id);
            supprimes++;
        }
        if (supprimes > 0) {
            markModified();
        }
        return supprimes;
    }

    /** Clients that don't send a group (older callers, tests) land in the default one. */
    /* ------------------------------ Découpage ------------------------------- */

    /**
     * Generates the vacations the edition's current créneaux — read as
     * amplitudes — would produce, without persisting anything: the découpage
     * preview.
     */
    public List<Creneau> previsualiserDecoupage() {
        List<Creneau> amplitudes = repository.listCreneaux();
        if (amplitudes.isEmpty()) {
            throw new IllegalArgumentException("Aucune amplitude à découper : l'édition n'a aucun créneau");
        }
        return VacationGeneratorService.genererVacations(amplitudes, getParametresDecoupage());
    }

    /**
     * Materializes the découpage <b>in place</b> (issue #172): the edition's
     * créneaux — the amplitudes just read — are replaced by the generated
     * vacations, and the persisted plan goes with them. Re-running with other
     * parameters means re-importing the scenario (or duplicating an
     * "amplitudes" edition first): the edition only ever holds one grid.
     */
    public void genererDecoupage() {
        List<Creneau> vacations = previsualiserDecoupage();
        repository.replaceCreneaux(vacations);
        markModified();
    }

    /**
     * Applies a scenario's optional {@code decoupageAuto:} section right after
     * its raw reference data is imported: the scenario's créneaux (amplitudes)
     * land in the edition, then the day-to-vacations découpage replaces them —
     * sparing the operator the manual "Découpage" screen round-trip after
     * every import of that scenario.
     */
    public void appliquerDecoupageAutomatique(PlanningFestival planning) {
        importFromPlanning(planning);
        genererDecoupage();
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
        validerPaireSansContradiction(contrainte);
        if (contrainte.getCreeLe() == null) {
            contrainte.setCreeLe(Instant.now());
        }
        repository.saveContrainte(contrainte);
        markModified();
        return contrainte;
    }

    /**
     * A pair declared both INCOMPATIBILITE and AFFINITE must be refused at
     * entry time, not silently arbitrated by the score (issue #80): the two
     * facts would pull the solver in opposite directions and the hard one
     * would always win without the user ever being told. The pair is the
     * unordered couple of the first two animateur ids — exactly what the
     * solver evaluates (see {@code AdHocConstraints}). Overwriting a
     * constraint under its own id is exempt: the saved version replaces the
     * conflicting one instead of coexisting with it.
     */
    private void validerPaireSansContradiction(ContrainteAdHoc contrainte) {
        TypeContrainteAdHoc typeOppose = switch (contrainte.getType()) {
            case AFFINITE -> TypeContrainteAdHoc.INCOMPATIBILITE;
            case INCOMPATIBILITE -> TypeContrainteAdHoc.AFFINITE;
            default -> null;
        };
        Set<String> paire = paireAnimateurs(contrainte);
        if (typeOppose == null || paire == null) {
            return;
        }
        listContraintesAdHoc().stream()
                .filter(existante -> existante.getType() == typeOppose)
                .filter(existante -> !existante.getId().equals(contrainte.getId()))
                .filter(existante -> paire.equals(paireAnimateurs(existante)))
                .findFirst()
                .ifPresent(existante -> {
                    throw new IllegalArgumentException(
                            "La paire d'animateurs " + String.join(" / ", new TreeSet<>(paire))
                                    + " est déjà visée par la contrainte " + existante.getId()
                                    + " (" + existante.getType()
                                    + ") : une même paire ne peut pas être déclarée à la fois incompatible et en affinité."
                                    + " Supprimez d'abord la contrainte existante.");
                });
    }

    /** The unordered pair of the first two animateur ids, or null when the constraint doesn't name a genuine pair. */
    private static Set<String> paireAnimateurs(ContrainteAdHoc contrainte) {
        List<Animateur> animateurs = contrainte.getAnimateursConcernes();
        if (animateurs == null || animateurs.size() < 2
                || animateurs.get(0) == null || animateurs.get(1) == null) {
            return null;
        }
        String premier = animateurs.get(0).getId();
        String second = animateurs.get(1).getId();
        if (premier == null || second == null || premier.equals(second)) {
            return null;
        }
        return Set.of(premier, second);
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
     * Records a lock, rejecting a target that does not match the type or does
     * not exist. Locking an already locked target is a no-op, not an error.
     */
    public VerrouillagePlanning createVerrouillage(VerrouillagePlanning verrouillage) {
        if (verrouillage.getType() == null) {
            throw new IllegalArgumentException("Type de verrouillage manquant");
        }
        normaliserCible(verrouillage);
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
            case ANIMATEUR_CRENEAU -> {
                String animateurId = requiredId(verrouillage.getAnimateurId(), "animateur id");
                if (!repository.animateurExists(animateurId)) {
                    throw new IllegalArgumentException("Animateur inconnu : " + animateurId);
                }
                Long creneauId = verrouillage.getCreneauId();
                if (creneauId == null) {
                    throw new IllegalArgumentException("Missing créneau id");
                }
                if (!repository.creneauExists(creneauId)) {
                    throw new IllegalArgumentException("Créneau inconnu : " + creneauId);
                }
                verrouillage.setStandId(null);
                verrouillage.setJour(null);
            }
        }
    }

    public void deleteVerrouillage(String id) {
        repository.deleteVerrouillage(id);
        markModified();
    }

    /* ----------------------- Espace animateur (jeton) ----------------------- */

    /** See {@link ReferenceDataRepository#compterImpactImport}. */
    public ReferenceDataRepository.ImpactImport compterImpactImport() {
        return repository.compterImpactImport();
    }

    /** See {@link ReferenceDataRepository#resoudreJetonAnimateur}. */
    public ReferenceDataRepository.ProprietaireJeton resoudreJetonAnimateur(String jeton) {
        return repository == null ? null : repository.resoudreJetonAnimateur(jeton);
    }

    /**
     * Rotates an animateur's espace access token. Not a {@code markModified()}
     * event: the token changes nothing the solver reads.
     *
     * @throws IllegalArgumentException when the animateur is unknown
     */
    public String regenererJetonAnimateur(String id) {
        String jeton = repository == null ? null : repository.regenererJetonAnimateur(id);
        if (jeton == null) {
            throw new IllegalArgumentException("Animateur inconnu : " + id);
        }
        return jeton;
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
    public void setContrainteActive(String nom, boolean actif) {
        if (repository != null) {
            repository.setContrainteActive(nom, actif);
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
