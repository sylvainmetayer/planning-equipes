package dev.sylvain.planning.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.ContrainteAdHoc;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.ModeGrilleCreneaux;
import dev.sylvain.planning.domain.Emplacement;
import dev.sylvain.planning.domain.ParametresDecoupage;
import dev.sylvain.planning.domain.ParametresLegaux;
import dev.sylvain.planning.domain.ParametresNotifications;
import dev.sylvain.planning.domain.ParametresSolveur;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.domain.VerrouillagePlanning;
import dev.sylvain.planning.service.journal.CurrentAction;
import dev.sylvain.planning.service.journal.ChampsModifies;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import dev.sylvain.planning.service.solve.SolverJobService;

/**
 * One door onto the whole reference model, for callers that legitimately need
 * several referentials at once — the JAX-RS resources, the MCP tools, the
 * solver's problem building.
 *
 * <p>It holds no logic of its own. Each referential is a service that owns its
 * validation and its writes ({@link StandService}, {@link AnimateurService},
 * {@link CreneauService}, {@link EmplacementService}, {@link TypologieService},
 * {@link ContrainteAdHocService}, {@link VerrouillageService},
 * {@link ParametresService}); a caller that only ever touches one of them
 * should inject that one directly. The facade is kept because roughly twenty
 * classes read across families, and making each of them assemble eight
 * injections would trade one large class for twenty smaller annoyances.</p>
 */
@ApplicationScoped
public class ReferenceDataService implements ReferenceData {

    @Inject
    ReferenceDataImportRepository imports;

    @Inject
    AnimateurService animateurs;

    @Inject
    StandService stands;

    @Inject
    EmplacementService emplacements;

    @Inject
    CreneauService creneaux;

    @Inject
    TypologieService typologies;

    @Inject
    ContrainteAdHocService contraintesAdHoc;

    @Inject
    VerrouillageService verrouillages;

    @Inject
    ParametresService parametres;

    @Inject
    ReferenceUsageService usages;

    @Inject
    ReferenceDataChangeTracker changeTracker;

    @Inject
    SolverJobService solverJobs;

    @Inject
    CoherenceService coherence;

    /**
     * Where the fields an edit changed are deposited for the history
     * (issue #406). The before-image is already read here for the write-time
     * warnings, so this is the one place that can tell {@code nom} moved from
     * a write that merely re-sent it.
     */
    @Inject
    CurrentAction currentAction;

    @Inject
    CreneauGridService grille;

    @Inject
    JdbcEditionScope scope;

    /* ------------------------------ Animateurs ----------------------------- */

    @Override
    public List<Animateur> listAnimateurs() {
        return animateurs.list();
    }

    public Animateur createAnimateur(Animateur animateur) {
        return animateurs.create(animateur);
    }

    /**
     * The bare write: no warning, and the history does not learn which fields
     * moved. Every caller outside this class goes through
     * {@link #writeAnimateur(String, Animateur)} — {@code LayeringStructuralTest}
     * holds it, after issue #392's A6 found the MCP tools on this one, writing
     * lines with an empty « champs » column and warning nobody.
     */
    public Animateur updateAnimateur(String id, Animateur animateur) {
        return animateurs.update(id, animateur);
    }

    /**
     * Creates the animateur, then reports what is worth a second look — an off
     * day outside the event, a birth date making them a jeune travailleur while
     * it runs. The write happens first and is never undone by what comes back:
     * see {@link Avertissement}.
     */
    public WrittenAnimateur writeAnimateur(Animateur animateur) {
        Animateur ecrit = createAnimateur(animateur);
        return new WrittenAnimateur(ecrit, coherence.onAnimateur(ecrit));
    }

    /**
     * Same as {@link #writeAnimateur(Animateur)}, for an edit of an existing
     * fiche — but reported against the fiche <b>as it stood before</b>, read
     * here and passed on.
     *
     * <p>Without that comparison the bulk edit would shout on every write: it
     * sends the whole merged fiche, date de naissance included, one
     * {@code PUT} per row, so adding a competence to thirty volunteers would
     * end on a snack bar listing every minor of the selection. Costs one read
     * of the roster per edit, the same order as the créneaux and stands the
     * warnings already read.</p>
     */
    public WrittenAnimateur writeAnimateur(String id, Animateur animateur) {
        Animateur avant = animateurs.list().stream()
                .filter(candidat -> Objects.equals(candidat.getId(), id))
                .findFirst()
                .orElse(null);
        Animateur ecrit = updateAnimateur(id, animateur);
        currentAction.champsModifies(ChampsModifies.surAnimateur(avant, ecrit));
        return new WrittenAnimateur(ecrit, coherence.onAnimateur(avant, ecrit));
    }

    public void deleteAnimateur(String id) {
        animateurs.delete(id);
    }

    public ReferenceUsage countAnimateurUsages(List<String> ids) {
        return usages.forAnimateurs(ids);
    }

    /* -------------------------------- Stands ------------------------------- */

    @Override
    public List<Stand> listStands() {
        return stands.list();
    }

    @Override
    public List<Stand> listSolvedStands() {
        return stands.listSolved();
    }

    /**
     * Persists the families a build assigned to the stands that had none, or
     * an invalid one (issue #390). A stand whose stored family already matches
     * is left alone, so a build on a settled edition writes nothing.
     */
    public void recordStandFamilies(List<Stand> stands, Map<String, Integer> familleParStand) {
        Map<String, Integer> aEcrire = new LinkedHashMap<>();
        for (Stand stand : stands) {
            Integer attribuee = familleParStand.get(stand.getId());
            if (attribuee != null && !attribuee.equals(stand.getFamille())) {
                stand.setFamille(attribuee);
                aEcrire.put(stand.getId(), attribuee);
            }
        }
        this.stands.recordFamilies(aEcrire);
    }

    public Stand createStand(Stand stand) {
        return stands.create(stand);
    }

    /** The bare write — see {@link #updateAnimateur(String, Animateur)}. */
    public Stand updateStand(String id, Stand stand) {
        return stands.update(id, stand);
    }

    /**
     * Creates the stand, then reports what its schedule leaves unread. Same
     * "written first, warned second" contract as {@link #writeAnimateur(Animateur)}.
     */
    public WrittenStand writeStand(Stand stand) {
        Stand ecrit = createStand(stand);
        return new WrittenStand(ecrit, coherence.onStand(null, ecrit));
    }

    /**
     * {@link #writeStand(Stand)} together with the typologies and the
     * emplacement the stand names and that do not exist yet — all written, or
     * none. Before this, {@code creer_stand_complet} wrote them in three
     * transactions and a stand refused on its third left the first two behind,
     * created without anyone being told (issue #392, A5). The typologies are
     * validated on the same connection, where the ones written a moment ago
     * are visible.
     */
    public WrittenStand writeStand(Stand stand, List<TypologieItem> typologiesACreer, Emplacement emplacementACreer) {
        Stand ecrit = scope.writeAndReturn("Failed to create stand " + stand.getId() + " with its dependencies",
                connection -> {
                    for (TypologieItem typologie : typologiesACreer) {
                        typologies.create(connection, typologie);
                    }
                    if (emplacementACreer != null) {
                        emplacements.create(connection, emplacementACreer);
                    }
                    return stands.create(connection, stand);
                });
        return new WrittenStand(ecrit, coherence.onStand(null, ecrit));
    }

    /**
     * Same as {@link #writeStand(Stand)}, for an edit — reported against the
     * stand as it stood before, so a write that leaves the schedule alone says
     * nothing about it (the bulk edit issues one {@code PUT} per row).
     */
    public WrittenStand writeStand(String id, Stand stand) {
        Stand avant = stands.find(id);
        Stand ecrit = updateStand(id, stand);
        currentAction.champsModifies(ChampsModifies.surStand(avant, ecrit));
        return new WrittenStand(ecrit, coherence.onStand(avant, ecrit));
    }

    public void deleteStand(String id) {
        stands.delete(id);
    }

    public ReferenceUsage countStandUsages(List<String> ids) {
        return usages.forStands(ids);
    }

    /** Declares what the edition's créneaux are; {@code null} or an unknown value is a 400. */
    public ParametresDecoupage updateModeGrille(String mode) {
        if (mode == null || mode.isBlank()) {
            throw new BusinessError.Invalid("modeGrille est requis : AMPLITUDES (journées à découper en vacations) "
                    + "ou VACATIONS (vacations finales, solvables telles quelles)");
        }
        return parametres.updateModeGrille(modeGrille(mode, "modeGrille"));
    }

    /**
     * A grid mode read from a request, as text: bound as an enum it would be
     * converted by the container, whose failure is a {@code 404} raised before
     * the method runs — and a mistyped field is a {@code 400}.
     */
    public static ModeGrilleCreneaux modeGrille(String valeur, String champ) {
        if (valeur == null || valeur.isBlank()) {
            return null;
        }
        try {
            return ModeGrilleCreneaux.valueOf(valeur.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BusinessError.Invalid(champ + " : valeur inconnue « " + valeur
                    + " », attendu AMPLITUDES ou VACATIONS");
        }
    }

    public HoraireCompaction.RapportCompactage compactHoraires(boolean apply) {
        return stands.compactHoraires(apply);
    }

    public List<GrilleHorairesStands.LigneGrille> saisirGrilleHoraires(
            List<GrilleHorairesStands.SaisieStand> saisies) {
        return stands.saisirGrille(saisies);
    }

    /* ----------------------------- Emplacements ----------------------------- */

    @Override
    public List<Emplacement> listEmplacements() {
        return emplacements.list();
    }

    public Emplacement createEmplacement(Emplacement emplacement) {
        return emplacements.create(emplacement);
    }

    public Emplacement updateEmplacement(String id, Emplacement emplacement) {
        return emplacements.update(id, emplacement);
    }

    public void deleteEmplacement(String id) {
        emplacements.delete(id);
    }

    /* ------------------------------ Timeslots ------------------------------ */

    @Override
    public List<Creneau> listCreneaux() {
        return creneaux.list();
    }

    public Creneau createCreneau(Creneau creneau) {
        return creneaux.create(creneau);
    }

    /** The bare write — see {@link #updateAnimateur(String, Animateur)}. */
    public Creneau updateCreneau(Long id, Creneau creneau) {
        return creneaux.update(id, creneau);
    }

    /**
     * Creates the timeslot, then reports whether any stand is open on it. Same
     * "written first, warned second" contract as
     * {@link #writeAnimateur(Animateur)}.
     */
    public WrittenCreneau writeCreneau(Creneau creneau) {
        Creneau ecrit = createCreneau(creneau);
        return new WrittenCreneau(ecrit, coherence.onCreneau(ecrit));
    }

    /**
     * Same as {@link #writeCreneau(Creneau)}, for an edit of an existing
     * timeslot — and the one place the history learns which of its fields
     * moved, hence the read of the grid beforehand (issue #406).
     */
    public WrittenCreneau writeCreneau(Long id, Creneau creneau) {
        Creneau avant = creneaux.find(id);
        Creneau ecrit = updateCreneau(id, creneau);
        currentAction.champsModifies(ChampsModifies.surCreneau(avant, ecrit));
        return new WrittenCreneau(ecrit, coherence.onCreneau(ecrit));
    }

    public void deleteCreneau(Long id) {
        creneaux.delete(id);
    }

    public List<Creneau> createCreneaux(List<Creneau> lot) {
        return creneaux.createInBulk(lot);
    }

    public int deleteCreneaux(Collection<Long> ids) {
        return creneaux.deleteInBulk(ids);
    }

    public ReferenceUsage countCreneauUsages(List<String> ids) {
        return usages.forCreneaux(ids);
    }

    /* ---------------------------- Grid as a whole ---------------------------- */

    /**
     * The grid's verdict, read in {@code mode} — the edition's declared mode
     * when the caller names none. Stands come resolved, exactly as the solver
     * reads them.
     */
    public CreneauGridService.RapportGrille controlerGrille(List<Creneau> creneaux, ModeGrilleCreneaux mode) {
        ParametresDecoupage decoupage = getParametresDecoupage();
        ModeGrilleCreneaux effectif = mode != null ? mode : decoupage.getModeGrille();
        // The stands are resolved against the grid being judged, not against the
        // persisted one: a preview of a grid the edition does not have yet — the
        // ordinary case when deriving or seeding it — would otherwise read every
        // rule-scheduled stand as open all day.
        List<Stand> stands = listStands();
        HoraireStandResolver.apply(stands, creneaux);
        return grille.validate(creneaux, stands, listAnimateurs(), effectif, decoupage, getParametresLegaux());
    }

    public CreneauGridService.RapportGrille controlerGrille(ModeGrilleCreneaux mode) {
        return controlerGrille(listCreneaux(), mode);
    }

    public CreneauGridService.DiagnosticGrille diagnoseGrille() {
        return CreneauGridService.diagnose(listCreneaux(), getParametresDecoupage());
    }

    /**
     * What a recurrence rule would add, and the verdict on the grid that would
     * result — nothing written.
     */
    public RecurrenceGrille previewRecurrence(CreneauGridService.RegleRecurrence regle,
            ModeGrilleCreneaux mode) {
        List<Creneau> generes = CreneauGridService.generateRecurrence(regle);
        List<Creneau> resultante = new ArrayList<>(listCreneaux());
        resultante.addAll(generes);
        return new RecurrenceGrille(generes, controlerGrille(resultante, mode));
    }

    /** Adds the rule's créneaux to the grid — never replacing it — and reports the verdict on the whole. */
    public RecurrenceGrille createRecurrence(CreneauGridService.RegleRecurrence regle, ModeGrilleCreneaux mode) {
        List<Creneau> crees = createCreneaux(CreneauGridService.generateRecurrence(regle));
        return new RecurrenceGrille(crees, controlerGrille(mode));
    }

    /** The créneaux a rule produced (or would produce), and the resulting grid's verdict. */
    public record RecurrenceGrille(List<Creneau> creneaux, CreneauGridService.RapportGrille controle) {
    }

    /**
     * The grid the stands' own hours imply, and the verdict on it — nothing
     * written. {@code remplacer} says which grid is judged: the derived one
     * alone, or the current grid plus the derived créneaux.
     */
    public DerivationGrille previewDerivation(GrilleDepuisFenetres.Parametres parametres, boolean remplacer,
            ModeGrilleCreneaux mode) {
        GrilleDepuisFenetres.Derivation derivation = GrilleDepuisFenetres.deriver(listStands(), parametres);
        List<Creneau> resultante = remplacer ? new ArrayList<>() : new ArrayList<>(listCreneaux());
        resultante.addAll(derivation.creneaux());
        return new DerivationGrille(derivation, controlerGrille(resultante, mode));
    }

    /** Writes the derived grid: added to the current one, or in its place (the persisted plan goes with it). */
    public DerivationGrille applyDerivation(GrilleDepuisFenetres.Parametres parametres, boolean remplacer,
            ModeGrilleCreneaux mode) {
        GrilleDepuisFenetres.Derivation derivation = GrilleDepuisFenetres.deriver(listStands(), parametres);
        if (derivation.creneaux().isEmpty()) {
            throw new BusinessError.Invalid("Aucun stand n'a de fenêtre d'ouverture sur ces dates : rien à dériver");
        }
        List<Creneau> ecrits = remplacer ? creneaux.replace(derivation.creneaux())
                : createCreneaux(derivation.creneaux());
        GrilleDepuisFenetres.Derivation persistee = new GrilleDepuisFenetres.Derivation(
                ecrits, derivation.coupures(), derivation.joursSansFenetre());
        return new DerivationGrille(persistee, controlerGrille(mode));
    }

    /** What the derivation produced (or would), and the verdict on the resulting grid. */
    public record DerivationGrille(GrilleDepuisFenetres.Derivation derivation,
            CreneauGridService.RapportGrille controle) {
    }

    /* ------------------------------- Slicing -------------------------------- */

    public List<Creneau> previewDecoupage() {
        return creneaux.previewDecoupage();
    }

    public void generateDecoupage() {
        creneaux.generateDecoupage();
    }

    /**
     * Applies a scenario's optional {@code decoupageAuto:} section right after
     * its raw reference data is imported: the scenario's créneaux (amplitudes)
     * land in the edition, then the day-to-vacations découpage replaces them —
     * sparing the operator the manual "Découpage" screen round-trip after
     * every import of that scenario. The one operation that genuinely spans
     * two referentials, hence its place here.
     */
    public void applyAutomaticDecoupage(PlanningEvenement planning) {
        importFromPlanning(planning);
        generateDecoupage();
    }

    /* ------------------------------ Typologies ----------------------------- */

    @Override
    public List<TypologieItem> listTypologies() {
        return typologies.list();
    }

    /** See {@link TypologieService#importer}: the write of a scenario's own section. */
    public TypologieItem importTypologie(TypologieItem typologie) {
        return typologies.importer(typologie);
    }

    public TypologieItem createTypologie(TypologieItem typologie) {
        return typologies.create(typologie);
    }

    public TypologieItem updateTypologie(String id, TypologieItem typologie) {
        return typologies.update(id, typologie);
    }

    public Optional<String> typologieNinja() {
        return typologies.ninja();
    }

    public void deleteTypologie(String id) {
        typologies.delete(id);
    }

    /* --------------------------- Ad hoc constraints ------------------------ */

    public List<ContrainteAdHoc> listContraintesAdHoc() {
        return contraintesAdHoc.list();
    }

    public ContrainteAdHoc createContrainteAdHoc(ContrainteAdHoc contrainte) {
        return contraintesAdHoc.create(contrainte);
    }

    /** See {@link ContrainteAdHocService#createAll} — all or none. */
    public List<ContrainteAdHoc> createContraintesAdHoc(List<ContrainteAdHoc> contraintes) {
        return contraintesAdHoc.createAll(contraintes);
    }

    public void deleteContrainteAdHoc(String id) {
        contraintesAdHoc.delete(id);
    }

    @Override
    public List<ContrainteAdHoc> snapshotContraintes() {
        return contraintesAdHoc.list();
    }

    /* --------------------------- Planning locks ----------------------------- */

    @Override
    public List<VerrouillagePlanning> listVerrouillages() {
        return verrouillages.list();
    }

    public VerrouillagePlanning createVerrouillage(VerrouillagePlanning verrouillage) {
        return verrouillages.create(verrouillage);
    }

    public void deleteVerrouillage(String id) {
        verrouillages.delete(id);
    }

    /* ----------------------- Espace animateur (token) ----------------------- */

    /** See {@link ReferenceDataImportRepository#countImportImpact}. */
    public ImportImpact countImportImpact() {
        return imports.countImportImpact();
    }

    /** See {@link AnimateurRepository#resolveAnimateurToken}. */
    public TokenOwner resolveAnimateurToken(String token) {
        return animateurs.resolveToken(token);
    }

    public String regenerateAnimateurToken(String id) {
        return animateurs.regenerateToken(id);
    }

    /** See {@link AnimateurRepository#resolveAbonnementToken}. */
    public TokenOwner resolveAbonnementToken(String token) {
        return animateurs.resolveAbonnementToken(token);
    }

    /** See {@link AnimateurRepository#abonnementToken}. */
    public String abonnementToken(String id) {
        return animateurs.abonnementToken(id);
    }

    /** See {@link AnimateurService#regenerateAbonnementToken}. */
    public String regenerateAbonnementToken(String id) {
        return animateurs.regenerateAbonnementToken(id);
    }

    /**
     * Replaces the whole persisted reference dataset with the one carried by a
     * (sample or solved) planning, so it becomes editable through the CRUD
     * endpoints. Every referential at once, in a single transaction — which is
     * why it belongs to the facade rather than to any one of them.
     */
    /**
     * Validates the stands a raw import carries, like every other write does
     * (issue #343): {@code /api/database/import} is the deliberate SQL back
     * door, this endpoint takes a domain object and must hold the same
     * invariant as the fiche, the matrix and the scenario file.
     */
    private static void checkImportedStands(PlanningEvenement planning) {
        if (planning == null || planning.getPostes() == null) {
            return;
        }
        planning.getPostes().stream()
                .map(PosteAffectation::getStand)
                .filter(Objects::nonNull)
                .distinct()
                .forEach(StandValidator::check);
    }

    public void importFromPlanning(PlanningEvenement planning) {
        // Refused while a solve holds this edition's solver: the landing persist
        // would re-insert the referential this import just replaced, old créneaux
        // reappearing by id beside the new ones, while emplacements, horaires and
        // ad hoc constraints stay wiped (issue #328).
        solverJobs.refuseIfSolving();
        checkImportedStands(planning);
        if (planning != null) {
            // Refused before anything is written: a file may not install a
            // combination of ad hoc exceptions the form itself refuses
            // (issue #84). The créneaux come from the planning's own seats —
            // the ones already persisted are about to be replaced.
            contraintesAdHoc.checkNoContradiction(planning.getContraintesAdHoc(), creneauxOf(planning));
        }
        imports.importFromPlanning(planning);
        changeTracker.markModified();
    }

    /** The distinct créneaux a planning's seats stand on, in encounter order. */
    private static List<Creneau> creneauxOf(PlanningEvenement planning) {
        if (planning.getPostes() == null) {
            return List.of();
        }
        Map<Long, Creneau> byId = new LinkedHashMap<>();
        for (PosteAffectation poste : planning.getPostes()) {
            if (poste.getCreneau() != null && poste.getCreneau().getId() != null) {
                byId.putIfAbsent(poste.getCreneau().getId(), poste.getCreneau());
            }
        }
        return List.copyOf(byId.values());
    }

    /* ------------------------------ Parameters ------------------------------ */

    @Override
    public ParametresLegaux getParametresLegaux() {
        return parametres.getLegaux();
    }

    public ParametresLegaux updateParametresLegaux(ParametresLegaux valeurs) {
        return parametres.updateLegaux(valeurs);
    }

    @Override
    public ParametresDecoupage getParametresDecoupage() {
        return parametres.getDecoupage();
    }

    public ParametresDecoupage updateParametresDecoupage(ParametresDecoupage valeurs) {
        return parametres.updateDecoupage(valeurs);
    }

    @Override
    public ParametresSolveur getParametresSolveur() {
        return parametres.getSolveur();
    }

    public ParametresSolveur updateParametresSolveur(ParametresSolveur valeurs) {
        return parametres.updateSolveur(valeurs);
    }

    public ParametresNotifications getParametresNotifications() {
        return parametres.getNotifications();
    }

    public ParametresNotifications updateParametresNotifications(ParametresNotifications valeurs) {
        return parametres.updateNotifications(valeurs);
    }

    @Override
    public Set<String> getContraintesDesactivees() {
        return parametres.disabledContraintes();
    }

    public void setContrainteActive(String nom, boolean actif) {
        parametres.setContrainteActive(nom, actif);
    }

    @Override
    public Map<String, Integer> getConstraintWeights() {
        return parametres.constraintWeights();
    }

    public void setConstraintWeight(String nom, Integer poids) {
        parametres.setConstraintWeight(nom, poids);
    }
}
