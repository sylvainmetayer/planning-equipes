package dev.sylvain.planning.service.referentiel;

import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.ConcurrentModificationGuard;
import dev.sylvain.planning.service.IdGenerator;
import dev.sylvain.planning.service.JdbcEditionScope;
import dev.sylvain.planning.service.ReferenceDataChangeTracker;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * The {@code typologie} referential — the vocabulary every other referential
 * points at. Stand competences, animateur competences and animateur wishes are
 * all typologie ids, which is why {@link #validateIds} lives here rather than
 * being copied into each of them: an unknown id is one message, written once.
 */
@ApplicationScoped
public class TypologieService implements TypologieLibelles {

    private final TypologieRepository repository;

    private final ReferenceDataChangeTracker changeTracker;

    private final ConcurrentModificationGuard staleWrites;

    @Inject
    public TypologieService(
            TypologieRepository repository,
            ReferenceDataChangeTracker changeTracker,
            ConcurrentModificationGuard staleWrites) {
        this.repository = repository;
        this.changeTracker = changeTracker;
        this.staleWrites = staleWrites;
    }

    @Inject
    IdGenerator ids;

    @Inject
    JdbcEditionScope scope;

    public List<TypologieItem> list() {
        return repository.listTypologies();
    }

    /**
     * The whole vocabulary in one read, for the documents that print labels
     * where the domain holds ids — one query per document rather than one per
     * typologie met along the way.
     */
    @Override
    public Map<String, String> labelsById() {
        return repository.listTypologies().stream()
                .collect(Collectors.toMap(
                        TypologieItem::id, TypologieItem::label, (premier, doublon) -> premier, LinkedHashMap::new));
    }

    /**
     * Creates the typologie under an id the application draws (ADR 0050): an
     * id the caller sent is ignored — it is what the returned item carries
     * that designates the new row.
     */
    public TypologieItem create(TypologieItem typologie) {
        TypologieItem cree = scope.writeAndReturn(
                "Failed to create typology " + typologie.label(), connection -> create(connection, typologie));
        changeTracker.markModified();
        return cree;
    }

    /**
     * {@link #create(TypologieItem)} inside a caller's transaction: written
     * with the rest, or not at all. The caller marks the referential modified
     * once its transaction is committed — marking it here would survive a
     * rollback, and the Solveur screen would announce data that never changed.
     */
    TypologieItem create(Connection connection, TypologieItem typologie) throws SQLException {
        String code = Codes.normalise(typologie.code(), IdGenerator.Kind.TYPOLOGIE);
        Codes.refuseTaken(code, repository.idByCode(connection, code), null, "la typologie");
        return repository.saveTypologie(
                connection,
                new TypologieItem(
                        ids.next(connection, IdGenerator.Kind.TYPOLOGIE),
                        code,
                        typologie.label(),
                        typologie.ninja(),
                        typologie.maxCreneauxParAnimateur(),
                        typologie.description(),
                        null),
                true);
    }

    /**
     * The write of an import: the row the file designates when it carries an
     * id the edition knows, a new one otherwise. No precondition — a file
     * replaces a referential, it does not edit a fiche.
     *
     * <p>A new row keeps an id of the typologie shape it arrives with: the
     * scenario import has already decided it (kept from the file, or drawn —
     * see {@code ScenarioIdRemap}), and drawing another would both lose the
     * file's id and, for a typologie without a code, make every re-import of
     * the same file create it once more. Any other id is a creation.</p>
     */
    public TypologieItem importer(TypologieItem typologie) {
        boolean existe = typologie.id() != null && repository.typologieExists(typologie.id());
        if (!existe && !IdGenerator.Kind.TYPOLOGIE.hasGeneratedShape(typologie.id())) {
            return create(typologie);
        }
        if (!existe) {
            // Never lower, so a no-op for an id the remap drew; for one it
            // kept, the counter was raised already — belt and braces.
            ids.raise(IdGenerator.Kind.TYPOLOGIE, IdGenerator.numberOf(IdGenerator.Kind.TYPOLOGIE, typologie.id()));
        }
        String code = Codes.normalise(typologie.code(), IdGenerator.Kind.TYPOLOGIE);
        Codes.refuseTaken(code, repository.idByCode(code), typologie.id(), "la typologie");
        TypologieItem ecrite = repository.saveTypologie(
                new TypologieItem(
                        typologie.id(),
                        code,
                        typologie.label(),
                        typologie.ninja(),
                        typologie.maxCreneauxParAnimateur(),
                        typologie.description(),
                        null),
                false);
        changeTracker.markModified();
        return ecrite;
    }

    public TypologieItem update(String id, TypologieItem typologie) {
        if (!repository.typologieExists(id)) {
            throw new BusinessError.NotFound("Typologie inconnue : " + id);
        }
        String code = Codes.normalise(typologie.code(), IdGenerator.Kind.TYPOLOGIE);
        Codes.refuseTaken(code, repository.idByCode(code), id, "la typologie");
        TypologieItem misAJour = repository.saveTypologie(
                new TypologieItem(
                        id,
                        code,
                        typologie.label(),
                        typologie.ninja(),
                        typologie.maxCreneauxParAnimateur(),
                        typologie.description(),
                        typologie.modifieLe()),
                false);
        changeTracker.markModified();
        return misAJour;
    }

    /** Id of the typologie carrying {@code code}, {@code null} when none does. */
    public String idByCode(String code) {
        return repository.idByCode(code);
    }

    public void delete(String id) {
        if (repository.typologieInUse(id)) {
            throw new BusinessError.Invalid(
                    "Typologie " + id + " utilisée par au moins un stand ou animateur — retirez-la d'abord");
        }
        repository.deleteTypologie(id);
        changeTracker.markModified();
    }

    /**
     * Id of the typologie flagged ninja, if any. Animateurs holding it are the
     * versatile profiles the solver may dispatch on any stand.
     */
    public Optional<String> ninja() {
        return repository.findTypologieNinja();
    }

    /**
     * The typologies a write names, as ids: each value is an id of the
     * referential or the code of one — codes and ids never overlap (see
     * {@link Codes#normalise(String, IdGenerator.Kind)}) — and a value that is
     * neither is left as written, for {@link #validateIds} to refuse by name.
     * What lets a REST or MCP caller say « STRATEGIE » rather than look its id up.
     */
    public Set<String> resolveIds(Set<String> valeurs) {
        if (valeurs == null || valeurs.isEmpty()) {
            return valeurs;
        }
        return resolveIds(valeurs, idsByKey());
    }

    /**
     * The edition's typologies, each under its id and under its code, mapped
     * to its id — read once, for a caller resolving several lists (a whole
     * file, an animateur's competences and wishes).
     */
    public Map<String, String> idsByKey() {
        return byIdOrCode(repository.listTypologies());
    }

    /** {@link #resolveIds(Set)} against an index {@link #idsByKey} already read. */
    public static Set<String> resolveIds(Collection<String> valeurs, Map<String, String> parCle) {
        if (valeurs == null) {
            return null;
        }
        return valeurs.stream()
                .map(valeur -> parCle.getOrDefault(valeur, valeur))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /** Same, for the keys of a competence map, against an index {@link #idsByKey} already read. */
    public static <V> Map<String, V> resolveKeys(Map<String, V> valeurs, Map<String, String> parCle) {
        if (valeurs == null) {
            return null;
        }
        Map<String, V> resolues = new LinkedHashMap<>();
        valeurs.forEach((cle, valeur) -> resolues.put(parCle.getOrDefault(cle, cle), valeur));
        return resolues;
    }

    private static Map<String, String> byIdOrCode(List<TypologieItem> referentiel) {
        Map<String, String> parCle = new HashMap<>();
        referentiel.forEach(typologie -> {
            if (typologie.code() != null) {
                parCle.put(typologie.code(), typologie.id());
            }
        });
        referentiel.forEach(typologie -> parCle.put(typologie.id(), typologie.id()));
        return parCle;
    }

    /**
     * Refuses ids the referential does not know. Reported all at once and
     * sorted: an import that names five unknown typologies should say so in
     * one message, not make the operator discover them one save at a time.
     */
    public void validateIds(Set<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        refuseUnknown(ids.stream()
                .filter(id -> !repository.typologieExists(id))
                .collect(Collectors.toCollection(TreeSet::new)));
    }

    /**
     * Same check inside a caller's transaction, where a typologie the same
     * unit of work has just created counts as known — on a fresh connection it
     * would not exist yet, and a stand created together with its typologies
     * would be refused for naming them.
     */
    public void validateIds(Connection connection, Set<String> ids) throws SQLException {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        Set<String> inconnues = new TreeSet<>();
        for (String id : ids) {
            if (!repository.typologieExists(connection, id)) {
                inconnues.add(id);
            }
        }
        refuseUnknown(inconnues);
    }

    private static void refuseUnknown(Set<String> inconnues) {
        if (!inconnues.isEmpty()) {
            throw new BusinessError.Invalid(
                    "Typologie(s) inconnue(s) : " + inconnues + " — créez-les d'abord via /api/typologies");
        }
    }
}
