package dev.sylvain.planning.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotFoundException;

/**
 * The {@code typologie} referential — the vocabulary every other referential
 * points at. Stand competences, animateur competences and animateur wishes are
 * all typologie ids, which is why {@link #validerIds} lives here rather than
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
                .collect(Collectors.toMap(TypologieItem::id, TypologieItem::label, (premier, doublon) -> premier,
                        LinkedHashMap::new));
    }

    public TypologieItem create(TypologieItem typologie) {
        TypologieItem cree = repository.saveTypologie(new TypologieItem(
                Ids.required(typologie.id(), "typology id"), typologie.label(), typologie.ninja(), null), true);
        changeTracker.markModified();
        return cree;
    }

    /**
     * The write of an import: a scenario's {@code typologies:} section names
     * ids the import has just auto-derived, so a taken id is the normal case
     * here and not the mistake {@link #create} refuses. No precondition
     * either — a file replaces a referential, it does not edit a fiche.
     */
    public TypologieItem importer(TypologieItem typologie) {
        TypologieItem ecrite = repository.saveTypologie(new TypologieItem(
                Ids.required(typologie.id(), "typology id"), typologie.label(), typologie.ninja(), null), false);
        changeTracker.markModified();
        return ecrite;
    }

    public TypologieItem update(String id, TypologieItem typologie) {
        if (!repository.typologieExists(id)) {
            throw new NotFoundException("Typology not found: " + id);
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
    void validerIds(Set<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        Set<String> inconnues = ids.stream()
                .filter(id -> !repository.typologieExists(id))
                .collect(Collectors.toCollection(TreeSet::new));
        if (!inconnues.isEmpty()) {
            throw new BusinessError.Invalid(
                    "Typologie(s) inconnue(s) : " + inconnues + " — créez-les d'abord via /api/typologies");
        }
    }
}
