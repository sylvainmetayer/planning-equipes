package dev.sylvain.planning.service;

import java.util.List;

import dev.sylvain.planning.domain.Groupe;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotFoundException;

/**
 * CRUD over the editions themselves — create "Année 2026", duplicate "Année
 * 2025" into it, rename one, drop one. See {@code docs/groupes.md} §6.
 */
@ApplicationScoped
public class GroupeService {

    /**
     * Timeslot group every new {@code groupe} starts with. A group holding no
     * {@code groupe_creneau} at all could not receive a single créneau (the FK
     * would have nothing to point at), so this is structural, not a
     * convenience. Everything else — stands, animateurs, typologies — starts
     * empty on purpose: a new edition is filled by importing a scenario or by
     * duplicating an existing group, not by inheriting someone else's data.
     */
    private static final String GROUPE_CRENEAU_INITIAL_ID = "DEFAUT";
    private static final String GROUPE_CRENEAU_INITIAL_NOM = "Défaut";

    @Inject
    GroupeRepository repository;

    @Inject
    GroupeContext groupeContext;

    @Inject
    ReferenceDataRepository referenceDataRepository;

    public List<Groupe> listGroupes() {
        return repository.listGroupes();
    }

    /**
     * The group the caller is actually working in, as resolved from its
     * {@code X-Groupe-Id} header. Lets the UI display the group it landed on
     * even when the header named one that no longer exists.
     */
    public Groupe groupeCourant() {
        String id = groupeContext.groupeIdCourant();
        return listGroupes().stream()
                .filter(groupe -> groupe.getId().equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Current group not found: " + id));
    }

    public Groupe creer(Groupe groupe) {
        Groupe cree = creerVide(groupe);
        initialiserGroupeCreneau(cree.getId());
        return cree;
    }

    public Groupe renommer(String id, Groupe groupe) {
        exigerExistant(id);
        String nom = requireNonBlank(groupe.getNom(), "nom du groupe");
        Groupe renomme = new Groupe(id, nom, false, null);
        repository.save(renomme);
        return renomme;
    }

    /**
     * Creates {@code cible} and fills it with a copy of {@code sourceId}'s
     * reference model — "2026 = 2025 minus the assignments". The solver results
     * are deliberately left out: they belong to the edition they were computed
     * for, and a fresh edition has nothing solved yet.
     */
    public Groupe dupliquer(String sourceId, Groupe cible) {
        exigerExistant(sourceId);
        // creerVide, not creer: the copy brings the source's own timeslot
        // groups over, and seeding a 'DEFAUT' one first would collide with it.
        Groupe cree = creerVide(cible);
        repository.dupliquer(sourceId, cree.getId());
        return cree;
    }

    private Groupe creerVide(Groupe groupe) {
        String id = requireNonBlank(groupe.getId(), "id du groupe");
        String nom = requireNonBlank(groupe.getNom(), "nom du groupe");
        if (repository.exists(id)) {
            throw new IllegalArgumentException("Un groupe portant l'identifiant " + id + " existe déjà");
        }
        Groupe cree = new Groupe(id, nom, false, null);
        repository.save(cree);
        groupeContext.invaliderCache();
        return cree;
    }

    /**
     * Drops the group and, by {@code ON DELETE CASCADE}, its whole reference
     * model. Three refusals, all of them recoverable states the UI must not be
     * able to walk into: the default group (nothing would be left to fall back
     * on), the group the caller is currently working in (every subsequent
     * screen would silently switch under them), and the last remaining one.
     */
    public void supprimer(String id) {
        exigerExistant(id);
        List<Groupe> groupes = listGroupes();
        if (groupes.size() <= 1) {
            throw new IllegalArgumentException("Impossible de supprimer le dernier groupe");
        }
        if (groupes.stream().anyMatch(groupe -> groupe.getId().equals(id) && groupe.isDefaut())) {
            throw new IllegalArgumentException(
                    "Impossible de supprimer le groupe par défaut — désignez-en un autre d'abord");
        }
        if (id.equals(groupeContext.groupeIdCourant())) {
            throw new IllegalArgumentException("Impossible de supprimer le groupe courant — basculez ailleurs d'abord");
        }
        repository.delete(id);
        groupeContext.invaliderCache();
    }

    /** Makes this group the fallback for any caller that designates none (export CLI, direct API call). */
    public void definirParDefaut(String id) {
        exigerExistant(id);
        repository.definirParDefaut(id);
        groupeContext.invaliderCache();
    }

    /** Seeds the new group's one timeslot group, inside that group's own scope. */
    private void initialiserGroupeCreneau(String id) {
        groupeContext.executeDans(id, () -> {
            referenceDataRepository.saveGroupeCreneau(new dev.sylvain.planning.domain.GroupeCreneau(
                    GROUPE_CRENEAU_INITIAL_ID, GROUPE_CRENEAU_INITIAL_NOM, false));
            referenceDataRepository.activerGroupeCreneau(GROUPE_CRENEAU_INITIAL_ID);
        });
    }

    private void exigerExistant(String id) {
        if (!repository.exists(id)) {
            throw new NotFoundException("Groupe not found: " + id);
        }
    }

    private static String requireNonBlank(String valeur, String champ) {
        if (valeur == null || valeur.isBlank()) {
            throw new IllegalArgumentException("Le " + champ + " est obligatoire");
        }
        return valeur;
    }
}
