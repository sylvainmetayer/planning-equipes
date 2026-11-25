package dev.sylvain.planning.service.compte;

import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.EditionContext;
import dev.sylvain.planning.service.edition.EditionRepository;
import dev.sylvain.planning.service.journal.JournalActionService;
import dev.sylvain.planning.service.referentiel.ReferenceDataService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Named accounts and what they may do: the person ({@code compte}) and the
 * rights delegated to them ({@code habilitation}: a role, an edition or all of
 * them, an expiry). Nothing deletes: an account is deactivated, a right is
 * withdrawn, so the journal can still say who held what, and when.
 *
 * <p>Sign-in through an OIDC provider is bound to them by {@link #identify}: no
 * account, no entry. The administrator of the configuration stays outside this
 * table, behind its own (closed-by-default) door.</p>
 */
@ApplicationScoped
public class CompteService {

    private static final String DERNIER_ADMINISTRATEUR =
            "Il doit rester au moins un administrateur actif : nommez-en un autre d'abord.";

    static final int EMAIL_MAX = 320;

    static final int NOM_MAX = 255;

    @Inject
    CompteRepository repository;

    @Inject
    EditionRepository editions;

    @Inject
    EditionContext editionContext;

    @Inject
    ReferenceDataService referentiel;

    @Inject
    JournalActionService journal;

    public List<Compte> list() {
        return repository.list();
    }

    /** Creates an account, to grant it a right before the person arrives. */
    public Compte create(String email, String nom) {
        String cle = normalize(email);
        String nomPropre = blankToNull(nom);
        if (nomPropre != null && nomPropre.length() > NOM_MAX) {
            throw new BusinessError.Invalid("Nom trop long (" + NOM_MAX + " caractères au plus).");
        }
        if (!repository.insertIfAbsent(UUID.randomUUID().toString(), cle, nomPropre)) {
            throw new BusinessError.Conflict("Un compte existe déjà pour cette adresse.");
        }
        return repository.findByEmail(cle).orElseThrow();
    }

    /** Refused for the account of the caller (one cannot lock oneself out) and for the last administrator. */
    public Compte deactivate(String id) {
        String par = acteur();
        if (id != null && id.equals(par)) {
            throw new BusinessError.Conflict("Vous ne pouvez pas désactiver votre propre compte.");
        }
        return setDeactivated(id, Instant.now(), par);
    }

    public Compte reactivate(String id) {
        return setDeactivated(id, null, acteur());
    }

    private Compte setDeactivated(String id, Instant le, String par) {
        switch (repository.setDeactivated(id, le, par)) {
            case NOT_FOUND -> throw new BusinessError.NotFound("Compte inconnu : " + id);
            case LAST_ADMIN -> throw new BusinessError.Conflict(DERNIER_ADMINISTRATEUR);
            case DONE -> {}
        }
        return required(id);
    }

    /**
     * Grants a right, in the name of the caller. An administrator is one of
     * the whole instance and never expires (an expiry would be a way to lose
     * the last one without anybody acting); an expiry already past would be a
     * right that never opened anything; the same right twice is a conflict.
     */
    public Compte grant(String compteId, RoleHabilitation role, String editionId, Instant expireLe) {
        return grant(compteId, role, editionId, expireLe, null);
    }

    /** Same, for a responsable de stand's right: {@code standIds} are the stands of {@code editionId} it covers. */
    public Compte grant(
            String compteId, RoleHabilitation role, String editionId, Instant expireLe, List<String> standIds) {
        required(compteId);
        if (role == null) {
            throw new BusinessError.Invalid("Rôle manquant.");
        }
        String edition = blankToNull(editionId);
        if (role == RoleHabilitation.ADMIN && edition != null) {
            throw new BusinessError.Invalid("Un administrateur l'est de toute l'instance, pas d'une édition.");
        }
        if (role == RoleHabilitation.ADMIN && expireLe != null) {
            throw new BusinessError.Invalid("Un droit d'administrateur n'expire pas : retirez-le quand il prend fin.");
        }
        if (edition != null && !editions.exists(edition)) {
            throw new BusinessError.Invalid("Édition inconnue : " + edition);
        }
        if (expireLe != null && !expireLe.isAfter(Instant.now())) {
            throw new BusinessError.Invalid("La date d'expiration est déjà passée.");
        }
        List<String> stands =
                standIds == null ? List.of() : standIds.stream().distinct().toList();
        if (role == RoleHabilitation.RESPONSABLE_STAND) {
            checkStands(edition, stands);
        } else if (!stands.isEmpty()) {
            throw new BusinessError.Invalid("Seul un responsable de stand porte des stands.");
        }
        Habilitation droit = new Habilitation(
                UUID.randomUUID().toString(), role, edition, expireLe, acteur(), null, null, null, stands);
        if (!repository.insertHabilitation(compteId, droit)) {
            throw new BusinessError.Conflict("Ce compte a déjà ce droit en vigueur.");
        }
        return required(compteId);
    }

    /** A responsable's right is one edition's and names at least one stand, each of that edition. */
    private void checkStands(String edition, List<String> stands) {
        if (edition == null) {
            throw new BusinessError.Invalid("Un responsable de stand l'est d'une édition précise.");
        }
        if (stands.isEmpty()) {
            throw new BusinessError.Invalid("Choisissez au moins un stand.");
        }
        Set<String> connus = editionContext.executeIn(
                edition,
                () -> referentiel.listStands().stream().map(Stand::getId).collect(Collectors.toSet()));
        stands.stream().filter(id -> !connus.contains(id)).findFirst().ifPresent(id -> {
            throw new BusinessError.Invalid("Stand inconnu dans cette édition : " + id);
        });
    }

    /** Refused when it is the last administrator right in force. */
    public Compte withdraw(String compteId, String habilitationId) {
        required(compteId);
        switch (repository.withdrawHabilitation(compteId, habilitationId, Instant.now(), acteur())) {
            case NOT_FOUND ->
                throw new BusinessError.NotFound(
                        "Aucune habilitation en vigueur " + habilitationId + " sur ce compte.");
            case LAST_ADMIN -> throw new BusinessError.Conflict(DERNIER_ADMINISTRATEUR);
            case DONE -> {}
        }
        return required(compteId);
    }

    /**
     * What to show for the account an actor id designates (a name, else its
     * address), or {@code null} when it is no account (the configuration's
     * own, the MCP key, nobody): resolved at read time, never stored.
     */
    public String nomAffiche(String acteurId) {
        if (acteurId == null) {
            return null;
        }
        return repository
                .findById(acteurId)
                .map(c -> c.nom() != null ? c.nom() : c.email())
                .orElse(null);
    }

    /** Called when an edition is deleted: the rights scoped to it open nothing any more. */
    public void withdrawForEdition(String editionId) {
        repository.withdrawForEdition(editionId, Instant.now(), Compte.SYSTEME_ID);
    }

    /** Who acts: an account id, never an address; the configuration's account when nobody is bound. */
    private String acteur() {
        return Optional.ofNullable(journal.nomAdmin()).orElse(Compte.CONFIG_ID);
    }

    /**
     * The account a provider's session belongs to, or empty: <b>nobody is
     * created by signing in</b>. An organiser is added here first (by their
     * address), then the provider vouches for them.
     *
     * <p>The stable subject is the key. An account without one is claimed by
     * the first session whose address matches — {@code emailTrusted}, the
     * provider vouching for it — and then belongs to that subject for good: the
     * same address held later by somebody else at the provider opens nothing,
     * and a changed address does not lock the owner out. A deactivated account
     * is returned as such, for the caller to refuse.</p>
     */
    public Optional<Compte> identify(String sujet, String email, boolean emailTrusted) {
        if (sujet == null || sujet.isBlank()) {
            return Optional.empty();
        }
        Optional<Compte> parSujet = repository.findBySubject(sujet);
        if (parSujet.isEmpty() && emailTrusted && email != null && !email.isBlank()) {
            // A deactivated account is never claimed: a refused sign-in leaves no trace on it.
            parSujet = repository
                    .findByEmail(email.trim())
                    .filter(CompteService::isActive)
                    .filter(compte -> repository.bindSubject(compte.id(), sujet));
        }
        parSujet.filter(CompteService::isActive).ifPresent(compte -> repository.touchSignIn(compte.id()));
        return parSujet;
    }

    /** An account not deactivated. */
    public static boolean isActive(Compte compte) {
        return compte.desactiveLe() == null;
    }

    /**
     * The roles an account carries on a request, from the rights in force:
     * {@code admin} (an administrator right) and {@code organisateur} (an
     * organiser right on any edition — which editions is {@link #editionScope}'s
     * question, asked where an edition is designated).
     */
    public static Set<String> roles(Compte compte, Instant now) {
        return inForce(compte, now)
                .map(h -> h.role().name().toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
    }

    /**
     * The editions an <b>organiser</b> account may work in: every one when a
     * right in force names none, else exactly the ones named. An account with
     * no organiser right opens none. The administrators are not asked.
     */
    public static Predicate<String> editionScope(Compte compte, Instant now) {
        List<Habilitation> droits = inForce(compte, now)
                .filter(h -> h.role() == RoleHabilitation.ORGANISATEUR)
                .toList();
        if (droits.stream().anyMatch(h -> h.editionId() == null)) {
            return id -> true;
        }
        Set<String> ids = droits.stream().map(Habilitation::editionId).collect(Collectors.toSet());
        return ids::contains;
    }

    private static Stream<Habilitation> inForce(Compte compte, Instant now) {
        return compte.habilitations().stream()
                .filter(h -> h.retireeLe() == null
                        && (h.expireLe() == null || h.expireLe().isAfter(now)));
    }

    private Compte required(String id) {
        return repository.findById(id).orElseThrow(() -> new BusinessError.NotFound("Compte inconnu : " + id));
    }

    /** Addresses are compared trimmed and case-insensitively, and must look like one. */
    static String normalize(String email) {
        if (email == null || email.isBlank()) {
            throw new BusinessError.Invalid("Adresse e-mail manquante.");
        }
        String cle = email.trim().toLowerCase(Locale.ROOT);
        if (cle.length() > EMAIL_MAX) {
            throw new BusinessError.Invalid("Adresse e-mail trop longue (" + EMAIL_MAX + " caractères au plus).");
        }
        if (!cle.matches("[^@\\s]+@[^@\\s]+")) {
            throw new BusinessError.Invalid("Adresse e-mail invalide.");
        }
        return cle;
    }

    private static String blankToNull(String valeur) {
        return valeur == null || valeur.isBlank() ? null : valeur.trim();
    }
}
