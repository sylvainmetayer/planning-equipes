package dev.sylvain.planning.service.referentiel;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.ConcurrentModificationGuard;
import dev.sylvain.planning.service.IdGenerator;
import dev.sylvain.planning.service.ReferenceDataChangeTracker;
import dev.sylvain.planning.service.TokenOwner;
import dev.sylvain.planning.service.keycloak.KeycloakUserProvisioning;
import dev.sylvain.planning.service.solve.SolverJobService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** CRUD of the animateur referential, plus the espace access token they are reached by. */
@ApplicationScoped
public class AnimateurService {

    private final AnimateurRepository repository;

    private final TypologieService typologies;

    private final ReferenceDataChangeTracker changeTracker;

    private final ConcurrentModificationGuard staleWrites;

    private final SolverJobService solverJobs;

    private final IdGenerator ids;

    private final GelReferentielService gel;

    @Inject
    public AnimateurService(
            AnimateurRepository repository,
            TypologieService typologies,
            ReferenceDataChangeTracker changeTracker,
            ConcurrentModificationGuard staleWrites,
            SolverJobService solverJobs,
            IdGenerator ids,
            GelReferentielService gel) {
        this.repository = repository;
        this.typologies = typologies;
        this.changeTracker = changeTracker;
        this.staleWrites = staleWrites;
        this.solverJobs = solverJobs;
        this.ids = ids;
        this.gel = gel;
    }

    /** No-op unless the Keycloak provisioning is on. */
    @Inject
    KeycloakUserProvisioning comptes;

    public List<Animateur> list() {
        return repository.listAnimateurs();
    }

    /**
     * Creates the fiche under an id the application draws (ADR 0050): an id
     * the caller sent is overwritten. Never one derived from the name — the
     * id is the one thing about an animateur that leaves over MCP.
     *
     * <p>With the Keycloak provisioning on, it also creates the account that
     * opens the espace. The fiche is written <b>first</b>: a save the
     * referential refuses must not leave a live account and a sent invitation
     * behind. A realm that refuses the account then takes the fiche back out,
     * so no fiche is left whose owner could never sign in.</p>
     */
    public Animateur create(Animateur animateur) {
        validate(animateur);
        animateur.setId(ids.next(IdGenerator.Kind.ANIMATEUR));
        repository.saveAnimateur(animateur, true);
        try {
            comptes.synchroniser(animateur, true);
        } catch (BusinessError.Conflict e) {
            repository.deleteAnimateur(animateur.getId());
            throw e;
        }
        changeTracker.markModified();
        return animateur;
    }

    /**
     * Saves the animateur as edited.
     *
     * <p>Refused while a solve holds this edition's solver, for the same reason
     * as {@link #delete}: the landing persist rewrites {@code prenom},
     * {@code nom}, {@code date_naissance} and {@code manager} from the animateur
     * captured when the problem was built, and rewrites their competences and
     * off-days wholesale — so an edit made meanwhile would quietly revert
     * minutes later. See {@link SolverJobService#refuseIfSolving}.</p>
     *
     * <p>The bulk edit of the referential screen is this same method, once per
     * row ({@code ReferenceDataStore.saveMany} issues one
     * {@code PUT /api/animateurs/{id}} per animateur), so there is no
     * server-side batch to check once.</p>
     *
     * <p>Under a {@link ReferentialFamily#COMPETENCES} freeze, refused only
     * when the competences move — compared with the fiche as stored, so the
     * person's own data (availability, wishes, e-mail) stays open, and so
     * does a declaration applied through this same method (ADR 0052).</p>
     */
    public Animateur update(String id, Animateur animateur) {
        solverJobs.refuseIfSolving();
        return update(id, animateur, () -> stored(id));
    }

    /**
     * {@link #update(String, Animateur)} for a caller that has already read
     * the fiche as stored — the facade, for its warnings; the competence grid,
     * for its rows — so a freeze compares against it instead of reading the
     * roster once more per row of a bulk edit.
     */
    public Animateur update(String id, Animateur animateur, Animateur avant) {
        solverJobs.refuseIfSolving();
        return update(id, animateur, () -> avant);
    }

    /** Both overloads, once each has refused a write while a solve runs. */
    private Animateur update(String id, Animateur animateur, Supplier<Animateur> avant) {
        if (!repository.animateurExists(id)) {
            throw new BusinessError.NotFound("Animateur inconnu : " + id);
        }
        animateur.setId(id);
        // Before the freeze compares: a competence may be named by the code of
        // its game category, and the stored fiche holds ids.
        validate(animateur);
        gel.refuseIfFrozen(
                ReferentialFamily.COMPETENCES, () -> GelReferentielService.changesCompetences(avant.get(), animateur));
        String ancienneAdresse = comptes.actif() ? repository.emailOf(id) : null;
        repository.saveAnimateur(animateur, false);
        changeTracker.markModified();
        boolean adresseChangee = !sameAddress(ancienneAdresse, animateur.getEmail());
        // After the save, so a stale read (#362) refused above provisions
        // nothing. The address the fiche left is retired like a deleted
        // fiche's: kept only while another fiche still carries it.
        comptes.synchroniser(animateur, adresseChangee);
        if (adresseChangee) {
            comptes.retirer(ancienneAdresse);
        }
        return animateur;
    }

    /**
     * Removes the animateur, and vacates the seats they held (see
     * {@link AnimateurRepository#deleteAnimateur}).
     *
     * <p>Refused while a solve holds the solver: that solve built its problem
     * from the referential as it stood at its start, and persisting its result
     * would re-insert the animateur — personal data coming back on its own,
     * minutes later. See {@link SolverJobService#refuseIfSolving}.</p>
     *
     * <p>With the Keycloak provisioning on, the account is disabled rather
     * than deleted, and only once no edition still expects the person — see
     * {@link KeycloakUserProvisioning}. A failure there does not fail the
     * delete: an account with no fiche opens nothing.</p>
     */
    public void delete(String id) {
        solverJobs.refuseIfSolving();
        // Read before the delete, used after it: the account is disabled only
        // once no fiche of ANY edition carries the address.
        String email = comptes.actif() ? repository.emailOf(id) : null;
        repository.deleteAnimateur(id);
        changeTracker.markModified();
        comptes.retirer(email);
    }

    private static boolean sameAddress(String avant, String apres) {
        String a = avant == null ? "" : avant.trim();
        String b = apres == null ? "" : apres.trim();
        return a.equalsIgnoreCase(b);
    }

    /** The fiche as stored — the before-image a freeze compares an edit against. */
    private Animateur stored(String id) {
        return repository.listAnimateurs().stream()
                .filter(candidat -> id.equals(candidat.getId()))
                .findFirst()
                .orElse(null);
    }

    /**
     * The identity first, then the typologie ids. Competences and wishes are
     * both typologie ids, checked against the same referential.
     */
    private void validate(Animateur animateur) {
        requireIdentity(animateur);
        // A typologie may be named by its code (ADR 0050): stored by its id.
        // One read of the referential for both lists, none when both are empty.
        boolean sansCompetences =
                animateur.getCompetences() == null || animateur.getCompetences().isEmpty();
        boolean sansSouhaits =
                animateur.getSouhaits() == null || animateur.getSouhaits().isEmpty();
        if (!sansCompetences || !sansSouhaits) {
            Map<String, String> parCle = typologies.idsByKey();
            animateur.setCompetences(TypologieService.resolveKeys(animateur.getCompetences(), parCle));
            animateur.setSouhaits(TypologieService.resolveIds(animateur.getSouhaits(), parCle));
        }
        if (animateur.getCompetences() != null) {
            typologies.validateIds(animateur.getCompetences().keySet());
        }
        if (animateur.getSouhaits() != null) {
            typologies.validateIds(animateur.getSouhaits());
        }
    }

    /**
     * Refuses a fiche without a prénom, a nom or a date de naissance, naming
     * every missing field in one sentence — the convention of the CSV import,
     * so the caller fixes the fiche once rather than field by field.
     *
     * <p>Not just a column constraint moved up: a blank name yields a fiche
     * nobody can recognise on a planning, and without a date de naissance
     * {@link Animateur#isMineurOn} and {@link Animateur#isMajeurOn} are
     * <b>both</b> false — the animateur silently escapes the minor regime and
     * the adult one alike. Shared by REST and MCP because both write through
     * here; the CSV import carries its own equivalent per row.</p>
     */
    static void requireIdentity(Animateur animateur) {
        List<String> missing = new ArrayList<>();
        if (animateur.getPrenom() == null || animateur.getPrenom().isBlank()) {
            missing.add("le prénom");
        }
        if (animateur.getNom() == null || animateur.getNom().isBlank()) {
            missing.add("le nom");
        }
        boolean birthDateMissing = animateur.getDateNaissance() == null;
        if (birthDateMissing) {
            missing.add("la date de naissance");
        }
        if (missing.isEmpty()) {
            return;
        }
        String fields = missing.size() == 1
                ? missing.getFirst()
                : String.join(", ", missing.subList(0, missing.size() - 1)) + " et " + missing.getLast();
        String verb = missing.size() == 1 ? " est obligatoire" : " sont obligatoires";
        String why =
                birthDateMissing ? " ; sans date de naissance, tout le régime mineur / majeur est indéterminé" : "";
        throw new BusinessError.Invalid("Fiche incomplète : " + fields + verb + why + ".");
    }

    /** See {@link AnimateurRepository#resolveAnimateurToken}. */
    public TokenOwner resolveToken(String token) {
        return repository.resolveAnimateurToken(token);
    }

    /**
     * Rotates an animateur's espace access token. Not a reference-data change:
     * the token changes nothing the solver reads.
     */
    public String regenerateToken(String id) {
        String token = repository.regenerateAnimateurToken(id);
        if (token == null) {
            throw new BusinessError.NotFound("Animateur inconnu : " + id);
        }
        return token;
    }

    /** See {@link AnimateurRepository#resolveAbonnementToken}. */
    public TokenOwner resolveAbonnementToken(String token) {
        return repository.resolveAbonnementToken(token);
    }

    /** See {@link AnimateurRepository#abonnementToken}. */
    public String abonnementToken(String id) {
        return repository.abonnementToken(id);
    }

    /**
     * Rotates an animateur's ICS subscription token. Independent of
     * {@link #regenerateToken}: the two credentials open different things, and
     * revoking a leaked calendar URL must not invalidate the espace link
     * printed on a PDF.
     */
    public String regenerateAbonnementToken(String id) {
        String token = repository.regenerateAbonnementToken(id);
        if (token == null) {
            throw new BusinessError.NotFound("Animateur inconnu : " + id);
        }
        return token;
    }
}
