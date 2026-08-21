package dev.sylvain.planning.service;

import java.util.List;

import dev.sylvain.planning.domain.Edition;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotFoundException;

/**
 * CRUD over the editions themselves — create "Année 2026", duplicate "Année
 * 2025" into it, rename one, drop one. See {@code docs/editions.md} §6.
 */
@ApplicationScoped
public class EditionService {


    @Inject
    EditionRepository repository;

    @Inject
    EditionContext editionContext;

    @Inject
    ReferenceDataRepository referenceDataRepository;

    public List<Edition> listEditions() {
        return repository.listEditions();
    }

    /**
     * The edition the caller is actually working in, as resolved from its
     * {@code X-Edition-Id} header. Lets the UI display the edition it landed on
     * even when the header named one that no longer exists.
     */
    public Edition editionCourante() {
        String id = editionContext.editionIdCourant();
        return listEditions().stream()
                .filter(edition -> edition.getId().equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Current edition not found: " + id));
    }

    public Edition creer(Edition edition) {
        return creerVide(edition);
    }

    public Edition renommer(String id, Edition edition) {
        exigerExistant(id);
        String nom = requireNonBlank(edition.getNom(), "nom de l'édition");
        Edition renomme = new Edition(id, nom, false, null);
        repository.save(renomme);
        return renomme;
    }

    /**
     * Creates {@code cible} and fills it with a copy of {@code sourceId}'s
     * reference model — "2026 = 2025 minus the assignments". The solver results
     * are deliberately left out: they belong to the edition they were computed
     * for, and a fresh edition has nothing solved yet.
     */
    public Edition dupliquer(String sourceId, Edition cible) {
        exigerExistant(sourceId);
        // creerVide, not creer: the copy brings the source's own timeslot
        // groups over, and seeding a 'DEFAUT' one first would collide with it.
        Edition cree = creerVide(cible);
        repository.dupliquer(sourceId, cree.getId());
        return cree;
    }

    private Edition creerVide(Edition edition) {
        String id = requireNonBlank(edition.getId(), "id de l'édition");
        String nom = requireNonBlank(edition.getNom(), "nom de l'édition");
        if (repository.exists(id)) {
            throw new ErreurMetier.Invalide("Une édition portant l'identifiant " + id + " existe déjà");
        }
        Edition cree = new Edition(id, nom, false, null);
        repository.save(cree);
        editionContext.invaliderCache();
        return cree;
    }

    /**
     * Drops the edition and, by {@code ON DELETE CASCADE}, its whole reference
     * model. Three refusals, all of them recoverable states the UI must not be
     * able to walk into: the default edition (nothing would be left to fall
     * back on), the edition the caller is currently working in (every
     * subsequent screen would silently switch under them), and the last
     * remaining one.
     */
    public void supprimer(String id) {
        exigerExistant(id);
        List<Edition> editions = listEditions();
        if (editions.size() <= 1) {
            throw new ErreurMetier.Invalide("Impossible de supprimer la dernière édition");
        }
        if (editions.stream().anyMatch(edition -> edition.getId().equals(id) && edition.isDefaut())) {
            throw new ErreurMetier.Invalide(
                    "Impossible de supprimer l'édition par défaut — désignez-en une autre d'abord");
        }
        if (id.equals(editionContext.editionIdCourant())) {
            throw new ErreurMetier.Invalide(
                    "Impossible de supprimer l'édition courante — basculez ailleurs d'abord");
        }
        repository.delete(id);
        editionContext.invaliderCache();
    }

    /** Makes this edition the fallback for any caller that designates none (export CLI, direct API call). */
    public void definirParDefaut(String id) {
        exigerExistant(id);
        repository.definirParDefaut(id);
        editionContext.invaliderCache();
    }

    /**
     * Resolves the edition a scenario's {@code edition:} section targets:
     * reuses it when it exists (its display name wins over the file's), or
     * creates it empty first. Reports which of the two happened — the UI must
     * show that recap to the operator.
     */
    public CibleImport resoudrePourImport(String id, String nom) {
        String idCible = requireNonBlank(id, "id de l'édition").trim();
        return listEditions().stream()
                .filter(edition -> edition.getId().equals(idCible))
                .findFirst()
                .map(edition -> new CibleImport(edition, false))
                .orElseGet(() -> {
                    // Only ids the import CREATES are constrained: an id is
                    // reused verbatim in headers, URLs and localStorage, so a
                    // file must not be able to smuggle an arbitrary blob in.
                    // Existing editions (whatever the UI let through) are
                    // matched above without this check.
                    if (!idCible.matches("[\\p{L}0-9][\\p{L}0-9 ._-]{0,63}")) {
                        throw new ErreurMetier.Invalide(
                                "Id d'édition invalide dans la section edition : lettres, chiffres, espaces,"
                                        + " points, tirets et tirets bas uniquement (64 caractères max).");
                    }
                    return new CibleImport(
                            creer(new Edition(idCible, nom == null || nom.isBlank() ? idCible : nom.trim(),
                                    false, null)),
                            true);
                });
    }

    /** Result of {@link #resoudrePourImport}: the edition to import into, and whether it was just created. */
    public record CibleImport(Edition edition, boolean creee) {
    }

    private void exigerExistant(String id) {
        if (!repository.exists(id)) {
            throw new NotFoundException("Edition not found: " + id);
        }
    }

    private static String requireNonBlank(String valeur, String champ) {
        if (valeur == null || valeur.isBlank()) {
            throw new ErreurMetier.Invalide("Le " + champ + " est obligatoire");
        }
        return valeur;
    }
}
