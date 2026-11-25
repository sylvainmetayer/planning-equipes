package dev.sylvain.planning.service.rh;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.analyse.RapportConformite;
import dev.sylvain.planning.service.referentiel.Avertissement;
import dev.sylvain.planning.service.referentiel.JoursEvenement;
import dev.sylvain.planning.service.referentiel.ReferenceDataService;
import dev.sylvain.planning.service.referentiel.TypeAvertissement;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * The contacts of the animateurs of the edition the call works in: read by the RH
 * right and the administrators, written by them and by the animateur for their own
 * fiche, and by nobody else.
 *
 * <p>A saved set for a minor with no legal representative is accepted — the field
 * is optional — with a warning that names the animateur by id, never by identity
 * (the browser keeps a log of what it showed).</p>
 */
@ApplicationScoped
public class ContactsService {

    static final int NOM_MAX = 100;
    static final int TELEPHONE_MAX = 30;
    private static final int CHIFFRES_MIN = 6;
    private static final Pattern TELEPHONE = Pattern.compile("\\+?[0-9 .()\\-]+");

    /** One line of the contacts screen. */
    @Schema(
            name = "ContactAnimateurView",
            requiredProperties = {"animateurId", "nom", "mineur", "contacts"})
    public record ContactView(String animateurId, String nom, boolean mineur, ContactsAnimateur contacts) {}

    /** What a save answers: the contacts as stored and what it is worth telling. */
    @Schema(
            name = "ContactsEnregistres",
            requiredProperties = {"contacts", "avertissements"})
    public record Saved(ContactsAnimateur contacts, List<Avertissement> avertissements) {}

    private final ContactsRepository repository;
    private final ReferenceDataService referenceData;

    @Inject
    public ContactsService(ContactsRepository repository, ReferenceDataService referenceData) {
        this.repository = repository;
        this.referenceData = referenceData;
    }

    /** Every animateur with something recorded, and every minor — the ones whose gap is worth seeing. */
    public List<ContactView> list() {
        Map<String, ContactsAnimateur> rows = repository.all();
        LocalDate reference = referenceDay();
        List<ContactView> vues = new ArrayList<>();
        for (Animateur animateur : referenceData.listAnimateurs()) {
            boolean mineur = animateur.isMineurOn(reference);
            ContactsAnimateur contacts = rows.get(animateur.getId());
            if (contacts != null || mineur) {
                vues.add(new ContactView(
                        animateur.getId(),
                        animateur.nomAffiche(),
                        mineur,
                        contacts == null ? ContactsAnimateur.VIDE : contacts));
            }
        }
        vues.sort(Comparator.comparing(ContactView::nom, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(ContactView::animateurId));
        return vues;
    }

    /** The minors of {@code planned} whose legal representative is not recorded. */
    public List<RapportConformite.MinorWithoutRepresentative> minorsWithoutRepresentative(
            List<RapportConformite.Personne> planned) {
        Map<String, ContactsAnimateur> rows = repository.all();
        return planned.stream()
                .filter(RapportConformite.Personne::mineur)
                .filter(p -> !rows.getOrDefault(p.animateurId(), ContactsAnimateur.VIDE)
                        .hasRepresentative())
                .map(p -> new RapportConformite.MinorWithoutRepresentative(p.animateurId(), p.nom()))
                .toList();
    }

    /** The contacts of one animateur, empty when nothing was recorded. */
    public ContactsAnimateur of(String animateurId) {
        required(animateurId);
        return repository.find(animateurId).orElse(ContactsAnimateur.VIDE);
    }

    /** Replaces the contacts of one animateur with what is sent, trimmed and checked. */
    public Saved save(String animateurId, ContactsAnimateur sent) {
        Animateur animateur = required(animateurId);
        ContactsAnimateur contacts = normalize(sent);
        repository.replace(animateurId, contacts);
        List<Avertissement> warnings = new ArrayList<>();
        if (animateur.isMineurOn(referenceDay()) && !contacts.hasRepresentative()) {
            warnings.add(new Avertissement(
                    TypeAvertissement.MINEUR_SANS_REPRESENTANT,
                    "L'animateur " + animateurId + " est mineur : son représentant légal n'est pas renseigné "
                            + "(facultatif, mais le rapport de conformité le signale)."));
        }
        return new Saved(contacts, warnings);
    }

    /** Blank to nothing, trimmed, bounded; a number must look like one. */
    static ContactsAnimateur normalize(ContactsAnimateur sent) {
        if (sent == null) {
            return ContactsAnimateur.VIDE;
        }
        return new ContactsAnimateur(
                name(sent.urgenceNom()),
                phone(sent.urgenceTelephone()),
                name(sent.representantNom()),
                phone(sent.representantTelephone()));
    }

    private static String name(String value) {
        String text = value == null || value.isBlank() ? null : value.trim();
        if (text != null && text.length() > NOM_MAX) {
            throw new BusinessError.Invalid("Un nom de contact tient en " + NOM_MAX + " caractères au plus.");
        }
        return text;
    }

    private static String phone(String value) {
        String text = value == null || value.isBlank() ? null : value.trim();
        if (text == null) {
            return null;
        }
        long digits = text.chars().filter(Character::isDigit).count();
        if (text.length() > TELEPHONE_MAX
                || digits < CHIFFRES_MIN
                || !TELEPHONE.matcher(text).matches()) {
            throw new BusinessError.Invalid("Numéro de téléphone invalide : des chiffres, des espaces et un « + » "
                    + "au début, " + TELEPHONE_MAX + " caractères au plus.");
        }
        return text;
    }

    private Animateur required(String animateurId) {
        return referenceData
                .findAnimateur(animateurId)
                .orElseThrow(() -> new BusinessError.NotFound("Animateur inconnu : " + animateurId));
    }

    /** The first day of the event, or today before any timeslot exists: a minor is read at the start. */
    private LocalDate referenceDay() {
        JoursEvenement jours = JoursEvenement.of(referenceData.listCreneaux());
        return jours.isEmpty() ? LocalDate.now() : jours.first();
    }
}
