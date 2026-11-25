package dev.sylvain.planning.service.rh;

import dev.sylvain.planning.domain.Edition;
import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.EditionContext;
import dev.sylvain.planning.service.analyse.RapportConformite;
import dev.sylvain.planning.service.analyse.RapportConformiteCsv;
import dev.sylvain.planning.service.analyse.RapportConformiteService;
import dev.sylvain.planning.service.compte.Compte;
import dev.sylvain.planning.service.compte.CompteService;
import dev.sylvain.planning.service.edition.EditionService;
import dev.sylvain.planning.service.export.RapportConformitePdf;
import dev.sylvain.planning.service.journal.JournalActionService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * What the RH right opens: the compliance report of an edition, on screen, as a
 * PDF and as a CSV. Every read goes through {@link #authorized}, which is the
 * only place the right is decided — the HTTP policy of {@code /api/rh/*} lets
 * an account in, this says <b>which editions</b>.
 *
 * <p><b>Each consultation and each export is journalled here</b>, in the edition
 * the report is about, and not left to the response filter: that one files an
 * action under the edition of the {@code X-Edition-Id} header, which an RH
 * account — named by the path, never by the header — does not necessarily
 * send. A journalling that depended on it would silently drop the one trace
 * this data must leave.</p>
 */
@ApplicationScoped
public class RhService {

    /** One edition an account may read, for the picker of a screen with no edition chosen. */
    @Schema(requiredProperties = {"id", "nom"})
    public record EditionAccessible(String id, String nom) {}

    private final EditionService editions;
    private final EditionContext editionContext;
    private final RapportConformiteService rapports;
    private final RapportConformitePdf pdf;
    private final JournalActionService journal;
    private final ContactsService contacts;

    @Inject
    public RhService(
            EditionService editions,
            EditionContext editionContext,
            RapportConformiteService rapports,
            RapportConformitePdf pdf,
            JournalActionService journal,
            ContactsService contacts) {
        this.editions = editions;
        this.editionContext = editionContext;
        this.rapports = rapports;
        this.pdf = pdf;
        this.journal = journal;
        this.contacts = contacts;
    }

    /** The editions the caller may read the report of: every one for an administrator, the ones of its RH rights otherwise. */
    public List<EditionAccessible> accessibleEditions(Compte compte, boolean admin) {
        Set<String> autorisees = compte == null ? Set.of() : CompteService.rhEditions(compte, Instant.now());
        return editions.listEditions().stream()
                .filter(e -> admin || autorisees.contains(e.getId()))
                .map(e -> new EditionAccessible(e.getId(), e.getNom()))
                .toList();
    }

    public RapportConformite rapport(Compte compte, boolean admin, String editionId) {
        return authorized(compte, admin, editionId, edition -> {
            RapportConformite base = rapports.build(editionId, edition.getNom());
            RapportConformite rapport =
                    base.withMinorsWithoutRepresentative(contacts.minorsWithoutRepresentative(base.personnes()));
            journal.recordAdminAction("CONFORMITE_CONSULTEE", null);
            return rapport;
        });
    }

    public byte[] pdf(Compte compte, boolean admin, String editionId) {
        return authorized(compte, admin, editionId, edition -> {
            byte[] document = pdf.render(withContacts(rapports.build(editionId, edition.getNom())));
            journal.recordAdminAction("EXPORT_CONFORMITE_PDF", null);
            return document;
        });
    }

    public String csv(Compte compte, boolean admin, String editionId) {
        return authorized(compte, admin, editionId, edition -> {
            String fichier = RapportConformiteCsv.write(withContacts(rapports.build(editionId, edition.getNom())));
            journal.recordAdminAction("EXPORT_CONFORMITE_CSV", null);
            return fichier;
        });
    }

    private RapportConformite withContacts(RapportConformite base) {
        return base.withMinorsWithoutRepresentative(contacts.minorsWithoutRepresentative(base.personnes()));
    }

    /** The contacts of the edition: every animateur holding some, and every minor. Journalled like the report. */
    public List<ContactsService.ContactView> contacts(Compte compte, boolean admin, String editionId) {
        return authorized(compte, admin, editionId, edition -> {
            List<ContactsService.ContactView> vues = contacts.list();
            journal.recordAdminAction("CONTACTS_CONSULTES", null);
            return vues;
        });
    }

    /** Saves the contacts of one animateur on behalf of the RH right or an administrator. */
    public ContactsService.Saved saveContacts(
            Compte compte, boolean admin, String editionId, String animateurId, ContactsAnimateur sent) {
        return authorized(compte, admin, editionId, edition -> {
            ContactsService.Saved saved = contacts.save(animateurId, sent);
            journal.recordAdminAction("CONTACTS_MODIFIES", animateurId);
            return saved;
        });
    }

    /**
     * Runs {@code lecture} in {@code editionId} for a caller entitled to it. An
     * edition that does not exist and one the caller has no right on answer
     * <b>the same way</b>, so the refusal tells nothing about the others.
     */
    private <T> T authorized(Compte compte, boolean admin, String editionId, Function<Edition, T> lecture) {
        Edition edition = editions.find(editionId == null ? "" : editionId).orElse(null);
        boolean droit = admin
                || (compte != null
                        && edition != null
                        && CompteService.rhEditions(compte, Instant.now()).contains(editionId));
        if (edition == null || !droit) {
            throw new BusinessError.NotFound("Aucune édition à cette adresse.");
        }
        return editionContext.executeIn(editionId, () -> lecture.apply(edition));
    }
}
