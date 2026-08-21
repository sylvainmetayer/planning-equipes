package dev.sylvain.planning.service;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.ContrainteAdHoc;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.Emplacement;
import dev.sylvain.planning.domain.ParametresDecoupage;
import dev.sylvain.planning.domain.ParametresLegaux;
import dev.sylvain.planning.domain.ParametresSolveur;
import dev.sylvain.planning.domain.PlanningFestival;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.domain.VerrouillagePlanning;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

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
public class ReferenceDataService implements Referentiel {

    @Inject
    ReferenceDataRepository repository;

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
    ReferenceDataChangeTracker changeTracker;

    /* ------------------------------ Animateurs ----------------------------- */

    @Override
    public List<Animateur> listAnimateurs() {
        return animateurs.list();
    }

    public Animateur createAnimateur(Animateur animateur) {
        return animateurs.create(animateur);
    }

    public Animateur updateAnimateur(String id, Animateur animateur) {
        return animateurs.update(id, animateur);
    }

    public void deleteAnimateur(String id) {
        animateurs.delete(id);
    }

    /* -------------------------------- Stands ------------------------------- */

    @Override
    public List<Stand> listStands() {
        return stands.list();
    }

    @Override
    public List<Stand> listStandsResolus() {
        return stands.listResolus();
    }

    public Stand createStand(Stand stand) {
        return stands.create(stand);
    }

    public Stand updateStand(String id, Stand stand) {
        return stands.update(id, stand);
    }

    public void deleteStand(String id) {
        stands.delete(id);
    }

    public CompactageHoraires.RapportCompactage compacterHoraires(boolean appliquer) {
        return stands.compacterHoraires(appliquer);
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

    public Creneau updateCreneau(Long id, Creneau creneau) {
        return creneaux.update(id, creneau);
    }

    public void deleteCreneau(Long id) {
        creneaux.delete(id);
    }

    public List<Creneau> createCreneaux(List<Creneau> lot) {
        return creneaux.createEnLot(lot);
    }

    public int deleteCreneaux(Collection<Long> ids) {
        return creneaux.deleteEnLot(ids);
    }

    /* ------------------------------ Découpage ------------------------------- */

    public List<Creneau> previsualiserDecoupage() {
        return creneaux.previsualiserDecoupage();
    }

    public void genererDecoupage() {
        creneaux.genererDecoupage();
    }

    /**
     * Applies a scenario's optional {@code decoupageAuto:} section right after
     * its raw reference data is imported: the scenario's créneaux (amplitudes)
     * land in the edition, then the day-to-vacations découpage replaces them —
     * sparing the operator the manual "Découpage" screen round-trip after
     * every import of that scenario. The one operation that genuinely spans
     * two referentials, hence its place here.
     */
    public void appliquerDecoupageAutomatique(PlanningFestival planning) {
        importFromPlanning(planning);
        genererDecoupage();
    }

    /* ------------------------------ Typologies ----------------------------- */

    @Override
    public List<TypologieItem> listTypologies() {
        return typologies.list();
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

    /* ----------------------- Espace animateur (jeton) ----------------------- */

    /** See {@link ReferenceDataRepository#compterImpactImport}. */
    public ReferenceDataRepository.ImpactImport compterImpactImport() {
        return repository.compterImpactImport();
    }

    /** See {@link ReferenceDataRepository#resoudreJetonAnimateur}. */
    public ReferenceDataRepository.ProprietaireJeton resoudreJetonAnimateur(String jeton) {
        return animateurs.resoudreJeton(jeton);
    }

    public String regenererJetonAnimateur(String id) {
        return animateurs.regenererJeton(id);
    }

    /**
     * Replaces the whole persisted reference dataset with the one carried by a
     * (sample or solved) planning, so it becomes editable through the CRUD
     * endpoints. Every referential at once, in a single transaction — which is
     * why it belongs to the facade rather than to any one of them.
     */
    public void importFromPlanning(PlanningFestival planning) {
        repository.importFromPlanning(planning);
        changeTracker.markModified();
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

    @Override
    public Set<String> getContraintesDesactivees() {
        return parametres.contraintesDesactivees();
    }

    public void setContrainteActive(String nom, boolean actif) {
        parametres.setContrainteActive(nom, actif);
    }
}
