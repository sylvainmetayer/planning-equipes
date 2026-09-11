package dev.sylvain.planning.service.referentiel;

import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.ConcurrentModificationGuard;
import dev.sylvain.planning.service.Ids;
import dev.sylvain.planning.service.ReferenceDataChangeTracker;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
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

    @Inject
    TypologieRepository repository;

    @Inject
    ReferenceDataChangeTracker changeTracker;

    @Inject
    ConcurrentModificationGuard staleWrites;

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

    public TypologieItem create(TypologieItem typologie) {
        TypologieItem cree = repository.saveTypologie(
                new TypologieItem(
                        Ids.required(typologie.id(), "typology id"), typologie.label(), typologie.ninja(), null),
                true);
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
        return repository.saveTypologie(
                connection,
                new TypologieItem(
                        Ids.required(typologie.id(), "typology id"), typologie.label(), typologie.ninja(), null),
                true);
    }

    /**
     * The write of an import: a scenario's {@code typologies:} section names
     * ids the import has just auto-derived, so a taken id is the normal case
     * here and not the mistake {@link #create} refuses. No precondition
     * either — a file replaces a referential, it does not edit a fiche.
     */
    public TypologieItem importer(TypologieItem typologie) {
        TypologieItem ecrite = repository.saveTypologie(
                new TypologieItem(
                        Ids.required(typologie.id(), "typology id"), typologie.label(), typologie.ninja(), null),
                false);
        changeTracker.markModified();
        return ecrite;
    }

    public TypologieItem update(String id, TypologieItem typologie) {
        if (!repository.typologieExists(id)) {
            throw new BusinessError.NotFound("Typologie inconnue : " + id);
        }
        TypologieItem misAJour = repository.saveTypologie(
                new TypologieItem(id, typologie.label(), typologie.ninja(), typologie.modifieLe()), false);
        changeTracker.markModified();
        return misAJour;
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
