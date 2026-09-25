package dev.sylvain.planning.service.edition;

import dev.sylvain.planning.domain.Edition;
import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.EditionContext;
import dev.sylvain.planning.service.IdGenerator;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * CRUD over the editions themselves — create "Année 2026", duplicate "Année
 * 2025" into it, rename one, drop one. See {@code docs/decisions/0001-cloisonnement-par-edition.md} §6.
 */
@ApplicationScoped
public class EditionService {

    private final EditionRepository repository;

    private final EditionContext editionContext;

    @Inject
    public EditionService(EditionRepository repository, EditionContext editionContext) {
        this.repository = repository;
        this.editionContext = editionContext;
    }

    @Inject
    IdGenerator ids;

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

    public Edition create(Edition edition) {
        return createEmpty(edition);
    }

    public Edition renommer(String id, Edition edition) {
        requireExisting(id);
        String nom = requireName(edition.getNom());
        Edition renomme = new Edition(id, nom, false, null);
        repository.save(renomme);
        return renomme;
    }

    /**
     * Creates {@code target} and fills it with a copy of {@code sourceId}'s
     * reference model — "2026 = 2025 minus the assignments". The solver results
     * are deliberately left out: they belong to the edition they were computed
     * for, and a fresh edition has nothing solved yet.
     *
     * <p>{@code avecAnimateurs} says whether the <b>people</b> come along
     * (issue #90). They do by default, which is the gesture issue #172 was
     * built for: a « canicule » edition duplicated mid-festival must keep its
     * roster and its « Envoyer à all ». They must not when the copy is a
     * <i>year template</i> — preparing 2027 from 2026 otherwise copies the
     * names, birth dates and e-mail addresses of people who have not signed up
     * again, which is a minimisation and retention problem
     * ({@code docs/rgpd.md}), not a convenience.</p>
     */
    public Edition duplicate(String sourceId, Edition target, boolean avecAnimateurs) {
        requireExisting(sourceId);
        // createEmpty, not create: the copy brings the source's own timeslot
        // groups over, and seeding a 'DEFAUT' one first would collide with it.
        Edition cree = createEmpty(target);
        repository.duplicate(sourceId, cree.getId(), avecAnimateurs);
        return cree;
    }

    /**
     * Creates an empty edition under an id the application draws (ADR 0050) —
     * {@code E} and a number; an id the caller sent is ignored.
     */
    private Edition createEmpty(Edition edition) {
        String nom = requireName(edition.getNom());
        Edition cree = new Edition(ids.nextEditionId(), nom, false, null);
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
    public void delete(String id) {
        requireExisting(id);
        List<Edition> editions = listEditions();
        if (editions.size() <= 1) {
            throw new BusinessError.Invalid("Impossible de supprimer la dernière édition");
        }
        if (editions.stream().anyMatch(edition -> edition.getId().equals(id) && edition.isDefaut())) {
            throw new BusinessError.Invalid(
                    "Impossible de supprimer l'édition par défaut — désignez-en une autre d'abord");
        }
        if (Objects.equals(id, editionContext.editionIdCourant())) {
            throw new BusinessError.Invalid("Impossible de supprimer l'édition courante — basculez ailleurs d'abord");
        }
        repository.delete(id);
        editionContext.invaliderCache();
    }

    /** Makes this edition the fallback for any caller that designates none (export CLI, direct API call). */
    public void setAsDefault(String id) {
        requireExisting(id);
        repository.setAsDefault(id);
        editionContext.invaliderCache();
    }

    /**
     * Resolves the edition a scenario's {@code edition:} section targets: the
     * edition of that id when it exists, else the one edition of that name,
     * else a new edition of that name. Its display name wins over the file's
     * when it exists. Reports which of the three happened — the UI must show
     * that recap to the operator.
     *
     * <p>The file's id designates, it never creates: a new edition gets the
     * next {@code E…} like any other (ADR 0050). A file naming no edition
     * name falls back on its id as the name, the shape of the files written
     * before, unless that id has an edition id's own shape.</p>
     */
    public ImportTarget resolveForImport(String id, String nom) {
        Optional<Edition> existante = findForImport(id, nom);
        if (existante.isPresent()) {
            return new ImportTarget(existante.get(), false);
        }
        return new ImportTarget(create(new Edition(null, nameToCreate(id, nom), false, null)), true);
    }

    /**
     * The edition a scenario's {@code edition} section designates, without
     * creating anything: by id first, else by name. Empty when the import
     * would create it. What both the import and its preview read, so the
     * dialog cannot announce another target than the one written.
     */
    public Optional<Edition> findForImport(String id, String nom) {
        String idCible = id == null ? "" : id.trim();
        List<Edition> editions = listEditions();
        if (!idCible.isEmpty()) {
            for (Edition edition : editions) {
                if (edition.getId().equals(idCible)) {
                    return Optional.of(edition);
                }
            }
        }
        String nomCree = nameToCreate(id, nom);
        List<Edition> homonymes = editions.stream()
                .filter(edition ->
                        edition.getNom() != null && edition.getNom().trim().equalsIgnoreCase(nomCree))
                .toList();
        if (homonymes.size() > 1) {
            throw new BusinessError.Invalid("Plusieurs éditions portent le nom « " + nomCree
                    + " » : désignez celle voulue par son id dans la section edition.");
        }
        return homonymes.stream().findFirst();
    }

    /**
     * The name an edition the section does not find is created under: its
     * nom, else its id — unless that id has an edition id's shape: it then
     * names an edition of another database, and « E5 » is no name to create
     * one under (see {@link #requireName}).
     */
    private static String nameToCreate(String id, String nom) {
        String nomCible = nom == null ? "" : nom.trim();
        if (!nomCible.isEmpty()) {
            return nomCible;
        }
        String idCible = id == null ? "" : id.trim();
        if (IdGenerator.looksLikeEditionId(idCible)) {
            throw new BusinessError.Invalid("Aucune édition « " + idCible + " » ici, et la section edition ne donne"
                    + " pas de nom pour la créer : ajoutez-lui un nom.");
        }
        if (idCible.isEmpty()) {
            throw new BusinessError.Invalid("La section edition doit donner le nom de l'édition cible.");
        }
        return idCible;
    }

    /** Result of {@link #resolveForImport}: the edition to import into, and whether it was just created. */
    public record ImportTarget(Edition edition, boolean creee) {}

    private void requireExisting(String id) {
        if (!repository.exists(id)) {
            throw new BusinessError.NotFound("Édition inconnue : " + id);
        }
    }

    /**
     * The name, trimmed. Refused when it has an edition id's shape (D5 of
     * ADR 0050): the MCP {@code edition} argument takes an id or a name and
     * tries the id first, so an edition named « E2 » could never be reached by
     * its name — or worse, would reach another edition.
     */
    private static String requireName(String nom) {
        String valeur = requireNonBlank(nom, "nom de l'édition").trim();
        if (IdGenerator.looksLikeEditionId(valeur)) {
            throw new BusinessError.Invalid("Le nom « " + valeur
                    + " » a la forme d'un identifiant d'édition (E suivi d'un nombre) : choisissez-en un autre,"
                    + " par exemple « Année 2027 ».");
        }
        return valeur;
    }

    private static String requireNonBlank(String valeur, String champ) {
        if (valeur == null || valeur.isBlank()) {
            throw new BusinessError.Invalid("Le " + champ + " est obligatoire");
        }
        return valeur;
    }
}
