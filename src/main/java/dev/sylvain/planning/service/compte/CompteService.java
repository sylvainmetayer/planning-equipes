package dev.sylvain.planning.service.compte;

import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.EditionContext;
import dev.sylvain.planning.service.edition.EditionRepository;
import dev.sylvain.planning.service.referentiel.StandRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Named accounts and what they may do (issues #294, #295, ADR 0049).
 *
 * <p>Keycloak says who is calling; this service remembers the person
 * ({@code compte}, created on their first sign-in or in advance by an
 * administrator) and the rights delegated to them in this application
 * ({@code habilitation}: a role, an edition or all of them, an expiry).
 * Deactivating an account here closes every door of the application to it,
 * whatever the realm still thinks — the kill switch an organiser can pull
 * without access to the Keycloak console.</p>
 *
 * <p>Called by {@code CompteIdentityAugmentor} on every authenticated request.
 * The account read is cached for {@link #FRAICHEUR}: a page opens a dozen API
 * calls at once, and a database round trip for each of them would be the
 * price of nothing — a deactivation or a withdrawn right still takes effect
 * within that delay, and at once on this instance since every write clears
 * the cache.</p>
 */
@ApplicationScoped
public class CompteService {

    static final Duration FRAICHEUR = Duration.ofSeconds(30);

    @Inject
    CompteRepository repository;

    @Inject
    EditionRepository editions;

    @Inject
    EditionContext editionContext;

    @Inject
    StandRepository stands;

    private final Map<String, Lu> cache = new ConcurrentHashMap<>();

    private record Lu(Compte compte, Instant le) {}

    /**
     * The account of a person who just presented a verified address, created
     * on the spot the first time. Records the sign-in at most once per
     * {@link #FRAICHEUR}, which is precise enough for « last seen » and keeps
     * the hot path a cache hit.
     *
     * <p>The token's subject comes first, the address second. The subject is
     * the person as the realm knows them and never changes; the address does —
     * a typo corrected in the console — and looking up by address alone would
     * then create a second account for the same subject, which the unique
     * index refuses on every request: the person locked out, and the
     * deactivation switch left on an account nobody signs in with any
     * more.</p>
     */
    public Compte signIn(String email, String nom, String sujet) {
        String cle = normalize(email);
        String cleCache = sujet == null ? "email:" + cle : "sub:" + sujet;
        Instant maintenant = Instant.now();
        Lu lu = cache.get(cleCache);
        // A cached account whose address is not the one presented is stale:
        // the realm just changed it, and following it is the point.
        if (lu != null
                && lu.le().plus(FRAICHEUR).isAfter(maintenant)
                && lu.compte().email().equals(cle)) {
            return lu.compte();
        }
        Compte compte = resolve(cle, nom, sujet, maintenant);
        cache.put(cleCache, new Lu(compte, maintenant));
        return compte;
    }

    private Compte resolve(String email, String nom, String sujet, Instant maintenant) {
        Optional<Compte> parSujet = sujet == null ? Optional.empty() : repository.findBySubject(sujet);
        if (parSujet.isPresent()) {
            Compte existant = parSujet.get();
            if (!existant.email().equalsIgnoreCase(email)) {
                // The realm changed the address. Followed unless another
                // account already holds the new one, in which case the person
                // keeps signing in on the account their subject names.
                repository.updateEmail(existant.id(), email);
            }
            repository.recordSignIn(existant.id(), nom, sujet, maintenant);
            return repository.findById(existant.id()).orElseThrow();
        }
        Optional<Compte> parAdresse = repository.findByEmail(email);
        if (parAdresse.isPresent()) {
            repository.recordSignIn(parAdresse.get().id(), nom, sujet, maintenant);
            return repository.findById(parAdresse.get().id()).orElseThrow();
        }
        // Two first sign-ins racing: the loser's insert does nothing, and the
        // read below returns the winner's row.
        repository.insertIfAbsent(newId(), email, nom, sujet, maintenant);
        return (sujet == null ? Optional.<Compte>empty() : repository.findBySubject(sujet))
                .or(() -> repository.findByEmail(email))
                .orElseThrow(() -> new IllegalStateException("Account neither created nor found for " + email));
    }

    /**
     * The security roles an account's rights grant on <b>every</b> edition,
     * as of now. A right scoped to one edition grants no role on its own: the
     * identity is built before any edition is bound to the request, so the
     * lot that opens a role checks {@link #rightsInForce} against the edition
     * the request resolved to.
     */
    public static Set<String> globalRoles(Compte compte, Instant maintenant) {
        if (!compte.actif()) {
            return Set.of();
        }
        return compte.habilitations().stream()
                .filter(h -> h.inForce(maintenant) && h.editionId() == null)
                .map(h -> h.role().securityRole())
                .collect(Collectors.toUnmodifiableSet());
    }

    /** The rights of an account in force on {@code editionId} (edition-wide ones included). */
    public static List<Habilitation> rightsInForce(Compte compte, String editionId, Instant maintenant) {
        if (!compte.actif()) {
            return List.of();
        }
        return compte.habilitations().stream()
                .filter(h -> h.inForce(maintenant))
                .filter(h -> h.editionId() == null || h.editionId().equals(editionId))
                .toList();
    }

    public List<Compte> list() {
        return repository.list();
    }

    /**
     * Creates an account ahead of the person's first sign-in, so a right can be
     * granted before they arrive. Signing in later with the same address
     * attaches to it.
     */
    public Compte create(String email, String nom) {
        String cle = normalize(email);
        if (!cle.matches("[^@\\s]+@[^@\\s]+")) {
            throw new BusinessError.Invalid("Adresse e-mail invalide : " + email);
        }
        if (!repository.insertIfAbsent(newId(), cle, blankToNull(nom), null, null)) {
            throw new BusinessError.Conflict("Un compte existe déjà pour " + cle + ".");
        }
        return repository.findByEmail(cle).orElseThrow();
    }

    public Compte deactivate(String id) {
        return setDeactivated(id, Instant.now());
    }

    public Compte reactivate(String id) {
        return setDeactivated(id, null);
    }

    private Compte setDeactivated(String id, Instant le) {
        if (!repository.setDeactivated(id, le)) {
            throw new BusinessError.NotFound("Compte inconnu : " + id);
        }
        cache.clear();
        return required(id);
    }

    /**
     * Grants a right. A {@link RoleHabilitation#RESPONSABLE_STAND} needs an
     * edition and at least one stand of it; no other role takes stands, and an
     * expiry already past would be a right that never opened anything.
     */
    public Compte grant(
            String compteId,
            RoleHabilitation role,
            String editionId,
            Instant expireLe,
            List<String> standIds,
            String creePar) {
        required(compteId);
        if (role == null) {
            throw new BusinessError.Invalid("Rôle manquant.");
        }
        String edition = blankToNull(editionId);
        if (edition != null && !editions.exists(edition)) {
            throw new BusinessError.Invalid("Édition inconnue : " + edition);
        }
        List<String> perimetre = standIds == null
                ? List.of()
                : standIds.stream()
                        .filter(s -> s != null && !s.isBlank())
                        .distinct()
                        .toList();
        if (role == RoleHabilitation.RESPONSABLE_STAND && (edition == null || perimetre.isEmpty())) {
            throw new BusinessError.Invalid(
                    "Un responsable de stand l'est dans une édition, pour au moins un de ses stands.");
        }
        List<String> inconnus = edition == null
                ? List.of()
                : editionContext.executeIn(
                        edition,
                        () -> perimetre.stream()
                                .filter(standId -> !stands.standExists(standId))
                                .toList());
        if (!inconnus.isEmpty()) {
            // Checked here rather than by a foreign key, which a dump import
            // would cascade through (see V108): a stand named by mistake is a
            // 400 the organiser can read, not a constraint violation.
            throw new BusinessError.Invalid(
                    "Stand(s) inconnu(s) dans l'édition " + edition + " : " + String.join(", ", inconnus));
        }
        if (role != RoleHabilitation.RESPONSABLE_STAND && !perimetre.isEmpty()) {
            throw new BusinessError.Invalid("Seul un responsable de stand a un périmètre de stands.");
        }
        if (expireLe != null && !expireLe.isAfter(Instant.now())) {
            throw new BusinessError.Invalid("La date d'expiration est déjà passée.");
        }
        repository.insertHabilitation(
                compteId,
                new Habilitation(
                        newId(),
                        role,
                        edition,
                        expireLe,
                        perimetre,
                        Optional.ofNullable(creePar).orElse("?"),
                        null,
                        null));
        cache.clear();
        return required(compteId);
    }

    public Compte withdraw(String compteId, String habilitationId) {
        required(compteId);
        if (!repository.withdrawHabilitation(compteId, habilitationId, Instant.now())) {
            throw new BusinessError.NotFound("Aucune habilitation en vigueur " + habilitationId + " sur ce compte.");
        }
        cache.clear();
        return required(compteId);
    }

    private Compte required(String id) {
        return repository.findById(id).orElseThrow(() -> new BusinessError.NotFound("Compte inconnu : " + id));
    }

    /** Addresses are compared trimmed and case-insensitively, as everywhere else. */
    static String normalize(String email) {
        if (email == null || email.isBlank()) {
            throw new BusinessError.Invalid("Adresse e-mail manquante.");
        }
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private static String blankToNull(String valeur) {
        return valeur == null || valeur.isBlank() ? null : valeur.trim();
    }

    private static String newId() {
        return UUID.randomUUID().toString();
    }
}
