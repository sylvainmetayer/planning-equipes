package dev.sylvain.planning.service.keycloak;

import dev.sylvain.planning.config.ConfigOidc;
import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.referentiel.AnimateurRepository;
import io.quarkus.logging.Log;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import org.keycloak.OAuth2Constants;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.KeycloakBuilder;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.admin.client.resource.UserResource;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;

/**
 * Mirrors the animateur referential into Keycloak accounts, so that creating a
 * fiche creates the account that opens the espace — the organiser maintains
 * one list, not two.
 *
 * <h2>One account is one person</h2>
 * <p>The key is the <b>e-mail address</b>, never the fiche. An animateur who
 * works two editions has two fiches and one account: they keep their password,
 * their second factor and their sign-in history from one year to the next, and
 * duplicating an edition creates no account at all.</p>
 *
 * <p>That is not only convenient, it is what Keycloak allows. A realm can let
 * people sign in with their e-mail address, or it can tolerate duplicate
 * addresses — never both, they are mutually exclusive options of the same
 * realm. Since signing in by e-mail is the requirement, addresses are unique,
 * and "one account per (edition, fiche)" is not on the table. The edition is
 * therefore decided where it already was: by the access token in the espace
 * URL (see {@code SessionEspaceFilter}).</p>
 *
 * <h2>What a failure does</h2>
 * <p>Asymmetric, on purpose.</p>
 * <ul>
 *   <li><b>Creating or editing a fiche</b> reports it outright if the
 *       account cannot be written, <em>after</em> the fiche is saved — so a
 *       save the referential refuses (an id taken, a stale read) never leaves
 *       a live account and a sent invitation behind. The account <i>is</i>
 *       the access: a fiche saved without one is a person who cannot open their
 *       espace, and nothing would say so until they try — weeks later, the
 *       day the planning is published. The organiser is at their screen right
 *       now and can retry.</li>
 *   <li><b>Deleting a fiche</b> never fails on this. The fiche is already
 *       gone, blocking its removal on the availability of an identity
 *       provider would be the worse outcome, and an account left enabled opens
 *       nothing by itself — every espace route still matches the account's
 *       address against a fiche that no longer exists. It is logged as an
 *       error, for an operator to disable the account by hand.</li>
 * </ul>
 *
 * <p>Bulk paths — the CSV import, a scenario import, duplicating an edition —
 * do not provision: an import would otherwise send one invitation per row
 * before anyone reviewed it. Those accounts are created on the next save of
 * the fiche (docs/keycloak.md).</p>
 *
 * <p>Accounts are <b>disabled, never deleted</b> — and only once no fiche in
 * any edition still carries the address. Deleting would destroy the sign-in
 * history of someone who may be back next year, and would break the person
 * still registered on another edition.</p>
 */
@ApplicationScoped
public class KeycloakUserProvisioning {

    private static final int HTTP_CREATED = 201;

    @Inject
    ConfigOidc config;

    @Inject
    AnimateurRepository repository;

    private volatile Keycloak client;

    public boolean actif() {
        return config.enabled() && config.provisioning().enabled();
    }

    /**
     * Creates or updates the account of one animateur, idempotently, once the
     * fiche is saved.
     *
     * <p>Called on every save of a fiche rather than only on creation: an
     * e-mail address that changes has to move the access with it, and a fiche
     * that gains an address it did not have is exactly the fiche whose owner
     * could not sign in until now.</p>
     *
     * @param nouvelleAdresse the fiche is new, or its address just changed:
     *                        only then is the {@code animateur} role granted
     *                        to an existing account. An ordinary edit — a
     *                        typo in a first name — must not give back a role
     *                        an administrator removed in the console to close
     *                        someone's espace.
     */
    public void synchroniser(Animateur animateur, boolean nouvelleAdresse) {
        if (!actif()) {
            return;
        }
        Optional<String> email = emailOf(animateur);
        if (email.isEmpty()) {
            // A fiche without an address is already a fiche whose owner cannot
            // open the espace — the address is the identity. Nothing to
            // provision, and refusing the save here would duplicate a rule the
            // referential does not have.
            return;
        }
        try {
            RealmResource realm = realm();
            Optional<UserRepresentation> existant = findByEmail(realm, email.get());
            if (existant.isPresent()) {
                update(realm, existant.get(), animateur, nouvelleAdresse);
            } else {
                create(realm, animateur, email.get());
            }
        } catch (RuntimeException e) {
            Log.errorf(e, "Keycloak provisioning failed for animateur %s", animateur.getId());
            throw new BusinessError.Conflict("Le compte Keycloak de cette fiche n'a pas pu être créé ou mis à "
                    + "jour (" + e.getMessage() + ") : sans compte, son titulaire ne peut pas ouvrir son "
                    + "espace. Réessayez d'enregistrer la fiche.");
        }
    }

    /**
     * Disables the account of a deleted fiche — unless another edition still
     * carries the same address, in which case the person is still expected and
     * the account stays open.
     */
    public void retirer(String email) {
        if (!actif() || email == null || email.isBlank()) {
            return;
        }
        String normalisee = email.trim().toLowerCase(Locale.ROOT);
        try {
            if (repository.emailAnimateurExiste(normalisee)) {
                Log.debugf("Keycloak account kept for %s: another fiche still carries the address", normalisee);
                return;
            }
            RealmResource realm = realm();
            findByEmail(realm, normalisee).ifPresent(utilisateur -> {
                utilisateur.setEnabled(false);
                realm.users().get(utilisateur.getId()).update(utilisateur);
                Log.infof("Keycloak account disabled for %s (no fiche left in any edition)", normalisee);
            });
        } catch (RuntimeException e) {
            // Never fails the delete: see the class javadoc.
            Log.errorf(e, "Keycloak account could not be disabled for %s", normalisee);
        }
    }

    private void create(RealmResource realm, Animateur animateur, String email) {
        UserRepresentation utilisateur = new UserRepresentation();
        utilisateur.setUsername(email);
        utilisateur.setEmail(email);
        utilisateur.setFirstName(animateur.getPrenom());
        utilisateur.setLastName(animateur.getNom());
        utilisateur.setEnabled(true);
        // Unverified on purpose: the invitation below is what makes the
        // address verified, and OidcAuthentication refuses a session whose
        // email_verified is false. Marking it verified here would hand the
        // espace to whoever the address was mistyped into.
        utilisateur.setEmailVerified(false);
        // `var` rather than the declared JAX-RS type: this class lives in
        // service/, which LayeringStructuralTest keeps free of transport types
        // — the admin client returns one, it is closed here, and it never
        // travels further.
        try (var reponse = realm.users().create(utilisateur)) {
            if (reponse.getStatus() != HTTP_CREATED) {
                throw new IllegalStateException(
                        "Keycloak a refusé la création du compte (HTTP " + reponse.getStatus() + ")");
            }
        }
        UserRepresentation cree = findByEmail(realm, email)
                .orElseThrow(() -> new IllegalStateException("Compte créé puis introuvable pour " + email));
        assignRole(realm, cree.getId());
        if (config.provisioning().sendInvitation()) {
            invite(realm, cree.getId(), email);
        }
        Log.infof("Keycloak account created for %s", email);
    }

    /**
     * Only the name is pushed on an update. The address is the key, so
     * changing it means the fiche now designates <em>another</em> person's
     * account (or a new one) — handled by the lookup above, not by renaming an
     * existing account out from under whoever else holds it. And the enabled
     * flag is left alone: an account an administrator disabled in the console
     * must not be silently reopened by an unrelated edit of a fiche.
     */
    private void update(
            RealmResource realm, UserRepresentation utilisateur, Animateur animateur, boolean nouvelleAdresse) {
        boolean change = false;
        if (!Objects.equals(utilisateur.getFirstName(), animateur.getPrenom())) {
            utilisateur.setFirstName(animateur.getPrenom());
            change = true;
        }
        if (!Objects.equals(utilisateur.getLastName(), animateur.getNom())) {
            utilisateur.setLastName(animateur.getNom());
            change = true;
        }
        if (change) {
            realm.users().get(utilisateur.getId()).update(utilisateur);
        }
        if (nouvelleAdresse) {
            assignRole(realm, utilisateur.getId());
        }
    }

    /**
     * Idempotent: Keycloak accepts a role the user already holds without complaining.
     *
     * <p>The role is looked up among the ones <b>assignable to this user</b>
     * rather than by reading the realm's own definition of it. The obvious
     * spelling — {@code realm.roles().get(name).toRepresentation()} — reads a
     * piece of realm configuration, which Keycloak guards with
     * {@code view-realm}; this service account holds {@code manage-users} and
     * nothing else, on purpose, so that call answers {@code 403 Forbidden} and
     * the account is created without its role: a person who can sign in and
     * reaches a page telling them they have no access. The "available realm
     * roles of a user" listing is part of managing that user, so
     * {@code manage-users} covers it — and the alternative was widening a
     * service account's rights to work around a lookup.</p>
     *
     * <p>Nothing short of a real Keycloak shows this: the permission is the
     * server's, not the client's.</p>
     *
     * <p>"Available" alone cannot be read as "already held", which is the
     * reason {@code listAll} is consulted too. A role the realm does not
     * define at all is absent from both listings — so a typo in {@code
     * OIDC_ANIMATEUR_ROLE}, or a realm configured without the role, would
     * otherwise be taken for "nothing to do" and hand out accounts that can
     * sign in and open nothing: exactly the failure this method exists to
     * prevent, arrived at silently. It is logged rather than thrown: the
     * account and its invitation are already out, and undoing them over a
     * misconfigured role would cost more than the role itself, which an
     * administrator can add from the console once the log says which one is
     * missing.</p>
     */
    private void assignRole(RealmResource realm, String userId) {
        String attendu = config.animateurRole();
        var roles = realm.users().get(userId).roles().realmLevel();

        Optional<RoleRepresentation> assignable = roles.listAvailable().stream()
                .filter(candidat -> attendu.equals(candidat.getName()))
                .findFirst();
        if (assignable.isPresent()) {
            roles.add(List.of(assignable.get()));
            return;
        }

        boolean dejaPorte = roles.listAll().stream().anyMatch(porte -> attendu.equals(porte.getName()));
        if (!dejaPorte) {
            Log.errorf(
                    "Le rôle « %s » n'existe pas dans le realm %s : le compte %s a été créé sans accès à son"
                            + " espace. Vérifiez OIDC_ANIMATEUR_ROLE et les rôles du realm.",
                    attendu, config.provisioning().realm(), userId);
        }
    }

    /**
     * The one mail this application does not send itself: Keycloak owns the
     * credentials, so Keycloak is what invites — by default to confirm the
     * address and register a passkey, with no password ever chosen
     * ({@code OIDC_PROVISIONING_INVITATION_ACTIONS}). A failure here does not undo the
     * account — an invitation can be resent from the console, an account that
     * exists nowhere cannot.
     */
    private void invite(RealmResource realm, String userId, String email) {
        try {
            UserResource utilisateur = realm.users().get(userId);
            utilisateur.executeActionsEmail(config.provisioning().invitationActions());
        } catch (RuntimeException e) {
            Log.warnf(e, "Keycloak invitation mail could not be sent to %s; the account exists", email);
        }
    }

    private Optional<UserRepresentation> findByEmail(RealmResource realm, String email) {
        List<UserRepresentation> trouves = realm.users().searchByEmail(email, true);
        return trouves == null || trouves.isEmpty() ? Optional.empty() : Optional.of(trouves.getFirst());
    }

    private static Optional<String> emailOf(Animateur animateur) {
        String email = animateur.getEmail();
        return email == null || email.isBlank()
                ? Optional.empty()
                : Optional.of(email.trim().toLowerCase(Locale.ROOT));
    }

    private RealmResource realm() {
        return client().realm(config.provisioning().realm());
    }

    /**
     * Built on first use rather than at startup: the application must boot
     * without Keycloak being up, and a deployment that never creates a fiche
     * never needs this connection at all.
     */
    private Keycloak client() {
        Keycloak courant = client;
        if (courant != null) {
            return courant;
        }
        synchronized (this) {
            if (client == null) {
                ConfigOidc.Provisioning provisioning = config.provisioning();
                client = KeycloakBuilder.builder()
                        .serverUrl(provisioning
                                .serverUrl()
                                .orElseThrow(() -> new IllegalStateException(
                                        "planning.auth.oidc.provisioning.server-url manquante")))
                        .realm(provisioning.realm())
                        .grantType(OAuth2Constants.CLIENT_CREDENTIALS)
                        .clientId(provisioning.clientId().orElse("planning-provisioning"))
                        .clientSecret(provisioning
                                .clientSecret()
                                .orElseThrow(() -> new IllegalStateException(
                                        "planning.auth.oidc.provisioning.client-secret manquante")))
                        .build();
            }
            return client;
        }
    }

    @PreDestroy
    synchronized void close() {
        if (client != null) {
            client.close();
            client = null;
        }
    }
}
