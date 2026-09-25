package dev.sylvain.planning.service.referentiel;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.ConcurrentModificationGuard;
import dev.sylvain.planning.service.Ids;
import dev.sylvain.planning.service.ReferenceDataChangeTracker;
import dev.sylvain.planning.service.TokenOwner;
import dev.sylvain.planning.service.solve.SolverJobService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;

/** CRUD of the animateur referential, plus the espace access token they are reached by. */
@ApplicationScoped
public class AnimateurService {

    private final AnimateurRepository repository;

    private final TypologieService typologies;

    private final ReferenceDataChangeTracker changeTracker;

    private final ConcurrentModificationGuard staleWrites;

    private final SolverJobService solverJobs;

    @Inject
    public AnimateurService(
            AnimateurRepository repository,
            TypologieService typologies,
            ReferenceDataChangeTracker changeTracker,
            ConcurrentModificationGuard staleWrites,
            SolverJobService solverJobs) {
        this.repository = repository;
        this.typologies = typologies;
        this.changeTracker = changeTracker;
        this.staleWrites = staleWrites;
        this.solverJobs = solverJobs;
    }

    public List<Animateur> list() {
        return repository.listAnimateurs();
    }

    public Animateur create(Animateur animateur) {
        animateur.setId(Ids.required(animateur.getId(), "animateur id"));
        validate(animateur);
        repository.saveAnimateur(animateur, true);
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
     */
    public Animateur update(String id, Animateur animateur) {
        solverJobs.refuseIfSolving();
        if (!repository.animateurExists(id)) {
            throw new BusinessError.NotFound("Animateur inconnu : " + id);
        }
        animateur.setId(id);
        validate(animateur);
        repository.saveAnimateur(animateur, false);
        changeTracker.markModified();
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
     */
    public void delete(String id) {
        solverJobs.refuseIfSolving();
        repository.deleteAnimateur(id);
        changeTracker.markModified();
    }

    /**
     * The identity first, then the typologie ids. Competences and wishes are
     * both typologie ids, checked against the same referential.
     */
    private void validate(Animateur animateur) {
        requireIdentity(animateur);
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
