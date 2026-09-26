package dev.sylvain.planning.service.keycloak;

import dev.sylvain.planning.config.ConfigOidc;
import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.referentiel.AnimateurRepository;
import io.quarkus.logging.Log;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
 * <h2>Bulk paths create, and invite later</h2>
 * <p>The CSV import, a scenario import and duplicating an edition create the
 * missing accounts — {@link #provisionMissing} — but send nothing: an import
 * would otherwise mail one invitation per row before anyone reviewed the
 * list. The organiser sends them afterwards, in one gesture, from the
 * animateurs screen ({@link #inviteAwaiting}). Until then the person can
 * still sign in, with the code by e-mail: entering it proves the mailbox, and
 * the authenticator marks the address verified.</p>
 *
 * <p>Accounts are <b>disabled, never deleted</b> — and only once no fiche in
 * any edition still carries the address. Deleting would destroy the sign-in
 * history of someone who may be back next year, and would break the person
 * still registered on another edition.</p>
 */
@ApplicationScoped
public class KeycloakUserProvisioning {

    private static final int HTTP_CREATED = 201;

    /** The realm role of administrators, the one the login flow asks a TOTP of. */
    private static final String ADMIN = "admin";

    /** How many accounts one page of the realm's user listing carries. */
    private static final int PAGE_UTILISATEURS = 200;

    /**
     * What a bulk pass did, address by address — one person counted once,
     * however many fiches carry their address.
     *
     * @param crees   accounts created
     * @param invites invitations Keycloak accepted to send
     * @param echecs  addresses the realm refused, detailed in the log
     */
    public record BilanComptes(int crees, int invites, int echecs) {
        static final BilanComptes RIEN = new BilanComptes(0, 0, 0);
    }

    /**
     * @param actif     whether this application provisions accounts at all
     * @param enAttente addresses of the edition whose owner was never
     *                  invited: no account, or one a bulk path created — what
     *                  {@link #inviteAwaiting} would mail
     */
    public record EtatInvitations(boolean actif, int enAttente) {}

    /** Where one address stands in the realm. */
    enum Situation {
        /** No account: created, then invited. */
        SANS_COMPTE,
        /** An account created by a bulk path, never invited nor signed in: invited. */
        NON_VERIFIE,
        /** Nothing to do. */
        PRET,
        /** Closed by an administrator: left alone, never reopened by a bulk pass. */
        DESACTIVE
    }

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
                create(realm, animateur, email.get(), config.provisioning().sendInvitation());
            }
        } catch (RuntimeException e) {
            Log.errorf(e, "Keycloak provisioning failed for animateur %s", animateur.getId());
            throw new BusinessError.Conflict("Le compte Keycloak de cette fiche n'a pas pu être créé ou mis à "
                    + "jour (" + e.getMessage() + ") : sans compte, son titulaire ne peut pas ouvrir son "
                    + "espace. Réessayez d'enregistrer la fiche.");
        }
    }

    /**
     * Creates, without mailing anyone, the accounts the given fiches lack —
     * the bulk paths' half of provisioning (see the class javadoc).
     *
     * <p>Never throws: the import it follows is already written, and undoing
     * it because the identity provider is down would lose the organiser's
     * work over something a later pass repairs. A failure is logged and
     * counted; {@link #invitationStatus} still reports the address as waiting,
     * and {@link #inviteAwaiting} creates what is missing.</p>
     */
    public BilanComptes provisionMissing(Collection<Animateur> fiches) {
        if (!actif()) {
            return BilanComptes.RIEN;
        }
        Map<String, Animateur> parAdresse = byAddress(fiches);
        if (parAdresse.isEmpty()) {
            return BilanComptes.RIEN;
        }
        try {
            RealmResource realm = realm();
            Map<String, UserRepresentation> comptes = accountsByAddress(realm);
            int crees = 0;
            int echecs = 0;
            for (Map.Entry<String, Animateur> entree : parAdresse.entrySet()) {
                if (situation(comptes.get(entree.getKey())) != Situation.SANS_COMPTE) {
                    continue;
                }
                try {
                    create(realm, entree.getValue(), entree.getKey(), false);
                    crees++;
                } catch (RuntimeException e) {
                    Log.errorf(e, "Keycloak account could not be created for %s", entree.getKey());
                    echecs++;
                }
            }
            return new BilanComptes(crees, 0, echecs);
        } catch (RuntimeException e) {
            Log.errorf(e, "Keycloak unreachable: %d account(s) left to create", parAdresse.size());
            return new BilanComptes(0, 0, parAdresse.size());
        }
    }

    /**
     * How many of these fiches' owners were never invited. Answers
     * {@code actif = false} rather than failing when provisioning is off, so
     * the screen simply shows nothing.
     */
    public EtatInvitations invitationStatus(Collection<Animateur> fiches) {
        if (!actif()) {
            return new EtatInvitations(false, 0);
        }
        Map<String, Animateur> parAdresse = byAddress(fiches);
        if (parAdresse.isEmpty()) {
            return new EtatInvitations(true, 0);
        }
        Map<String, UserRepresentation> comptes = readAccounts();
        long enAttente = parAdresse.keySet().stream()
                .map(adresse -> situation(comptes.get(adresse)))
                .filter(KeycloakUserProvisioning::awaitsInvitation)
                .count();
        return new EtatInvitations(true, (int) enAttente);
    }

    /**
     * The organiser's explicit gesture after an import: every owner of these
     * fiches who was never invited receives the invitation — the account
     * created first when missing — and counts as invited from then on, so a
     * second press mails nobody twice.
     *
     * <p>Sent whatever {@code OIDC_PROVISIONING_SEND_INVITATION} says: that
     * setting governs the mail a single save sends on its own, and this is
     * someone pressing « send ».</p>
     */
    public BilanComptes inviteAwaiting(Collection<Animateur> fiches) {
        if (!actif()) {
            throw new BusinessError.Conflict(
                    "La création des comptes Keycloak est désactivée : il n'y a pas d'invitation à envoyer.");
        }
        Map<String, Animateur> parAdresse = byAddress(fiches);
        if (parAdresse.isEmpty()) {
            return BilanComptes.RIEN;
        }
        RealmResource realm = realm();
        Map<String, UserRepresentation> comptes = readAccounts();
        int crees = 0;
        int invites = 0;
        int echecs = 0;
        for (Map.Entry<String, Animateur> entree : parAdresse.entrySet()) {
            String adresse = entree.getKey();
            UserRepresentation compte = comptes.get(adresse);
            Situation situation = situation(compte);
            if (!awaitsInvitation(situation)) {
                continue;
            }
            try {
                String id;
                if (situation == Situation.SANS_COMPTE) {
                    id = create(realm, entree.getValue(), adresse, false);
                    crees++;
                } else {
                    id = compte.getId();
                }
                sendInvitation(realm, id);
                markInvited(realm, id);
                invites++;
            } catch (RuntimeException e) {
                Log.errorf(e, "Keycloak invitation could not be sent to %s", adresse);
                echecs++;
            }
        }
        Log.infof("Keycloak invitations: %d sent, %d account(s) created, %d failed", invites, crees, echecs);
        return new BilanComptes(crees, invites, echecs);
    }

    /** The first fiche for each address, addresses normalised; fiches without one are skipped. */
    static Map<String, Animateur> byAddress(Collection<Animateur> fiches) {
        Map<String, Animateur> parAdresse = new LinkedHashMap<>();
        for (Animateur fiche : fiches) {
            emailOf(fiche).ifPresent(adresse -> parAdresse.putIfAbsent(adresse, fiche));
        }
        return parAdresse;
    }

    static Situation situation(UserRepresentation compte) {
        if (compte == null) {
            return Situation.SANS_COMPTE;
        }
        if (!Boolean.TRUE.equals(compte.isEnabled())) {
            return Situation.DESACTIVE;
        }
        return Boolean.TRUE.equals(compte.isEmailVerified()) ? Situation.PRET : Situation.NON_VERIFIE;
    }

    static boolean awaitsInvitation(Situation situation) {
        return situation == Situation.SANS_COMPTE || situation == Situation.NON_VERIFIE;
    }

    /**
     * The realm's accounts, for a screen or a gesture that must report a
     * Keycloak it cannot reach rather than pretend there is nothing to do.
     */
    private Map<String, UserRepresentation> readAccounts() {
        try {
            return accountsByAddress(realm());
        } catch (RuntimeException e) {
            Log.errorf(e, "Keycloak accounts could not be listed");
            throw new BusinessError.Conflict(
                    "Keycloak ne répond pas (" + e.getMessage() + ") : réessayez dans un instant.");
        }
    }

    /**
     * Every account of the realm, by address, read page by page: one listing
     * rather than one search per fiche, which an import of a few hundred rows
     * would otherwise cost.
     */
    private Map<String, UserRepresentation> accountsByAddress(RealmResource realm) {
        Map<String, UserRepresentation> parAdresse = new HashMap<>();
        for (int debut = 0; ; debut += PAGE_UTILISATEURS) {
            List<UserRepresentation> page = realm.users().list(debut, PAGE_UTILISATEURS);
            if (page == null) {
                break;
            }
            for (UserRepresentation compte : page) {
                if (compte.getEmail() != null && !compte.getEmail().isBlank()) {
                    parAdresse.put(compte.getEmail().trim().toLowerCase(Locale.ROOT), compte);
                }
            }
            if (page.size() < PAGE_UTILISATEURS) {
                break;
            }
        }
        return parAdresse;
    }

    /**
     * Makes {@code email} an administrator: its account created when missing
     * — verified, invited like an animateur's — then the realm role
     * {@code admin} granted. The realm role and not a right of this
     * application's own: the login flow imposes the TOTP second factor on
     * that role, so an administrator made anywhere else would sign in without
     * one. The next sign-in asks for the TOTP to be configured.
     *
     * @return whether the account was created, hence invited, just now
     */
    public boolean grantAdmin(String email, String nom) {
        if (!actif()) {
            throw new BusinessError.Conflict("La création des comptes Keycloak est désactivée : donnez le rôle " + ADMIN
                    + " depuis la console Keycloak.");
        }
        String adresse = email.trim().toLowerCase(Locale.ROOT);
        try {
            RealmResource realm = realm();
            Optional<UserRepresentation> existant = findByEmail(realm, adresse);
            if (existant.isPresent() && !Boolean.TRUE.equals(existant.get().isEnabled())) {
                throw new BusinessError.Conflict("Le compte Keycloak de " + adresse
                        + " est désactivé : réactivez-le depuis la console Keycloak avant d'en faire un"
                        + " administrateur.");
            }
            String id = existant.isPresent() ? existant.get().getId() : createAccount(realm, adresse, nom, null, true);
            if (!assignRole(realm, id, ADMIN)) {
                // Logged, not thrown, for an animateur; an administrator
                // who is not one after all is a failure to report.
                throw new IllegalStateException("le rôle " + ADMIN + " n'existe pas dans le realm");
            }
            Log.infof("Keycloak realm role %s granted to %s", ADMIN, adresse);
            return existant.isEmpty();
        } catch (BusinessError e) {
            throw e;
        } catch (RuntimeException e) {
            Log.errorf(e, "Keycloak realm role %s could not be granted to %s", ADMIN, adresse);
            throw new BusinessError.Conflict(
                    "Keycloak n'a pas accepté le rôle administrateur (" + e.getMessage() + ") : réessayez.");
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

    /** @return the id Keycloak gave the account */
    private String create(RealmResource realm, Animateur animateur, String email, boolean inviter) {
        String id = createAccount(realm, email, animateur.getPrenom(), animateur.getNom(), inviter);
        assignRole(realm, id, config.animateurRole());
        return id;
    }

    /**
     * The account alone, no role: an animateur's gets {@code animateur}, an
     * administrator's {@code admin} — and neither the other's.
     *
     * @return the id Keycloak gave the account
     */
    private String createAccount(RealmResource realm, String email, String prenom, String nom, boolean inviter) {
        UserRepresentation utilisateur = new UserRepresentation();
        utilisateur.setUsername(email);
        utilisateur.setEmail(email);
        utilisateur.setFirstName(prenom);
        utilisateur.setLastName(nom);
        utilisateur.setEnabled(true);
        // Verified from the start when the invitation leaves with it: the
        // account has no password, so the only way in is a code sent to this
        // very mailbox — the proof "verified" stands for, made at each sign-in
        // (the email-code authenticator marks it too). Left unverified by a
        // bulk path, which invites nobody: that is how inviteAwaiting knows
        // who still waits for their invitation.
        utilisateur.setEmailVerified(inviter);
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
        if (inviter) {
            invite(realm, cree.getId(), email);
        }
        Log.infof("Keycloak account created for %s", email);
        return cree.getId();
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
            assignRole(realm, utilisateur.getId(), config.animateurRole());
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
    private boolean assignRole(RealmResource realm, String userId, String attendu) {
        var roles = realm.users().get(userId).roles().realmLevel();

        Optional<RoleRepresentation> assignable = roles.listAvailable().stream()
                .filter(candidat -> attendu.equals(candidat.getName()))
                .findFirst();
        if (assignable.isPresent()) {
            roles.add(List.of(assignable.get()));
            return true;
        }

        boolean dejaPorte = roles.listAll().stream().anyMatch(porte -> attendu.equals(porte.getName()));
        if (!dejaPorte) {
            Log.errorf(
                    "Le rôle « %s » n'existe pas dans le realm %s : le compte %s a été créé sans ce rôle."
                            + " Vérifiez les rôles du realm (et OIDC_ANIMATEUR_ROLE pour les animateurs).",
                    attendu, config.provisioning().realm(), userId);
        }
        return dejaPorte;
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
            sendInvitation(realm, userId);
        } catch (RuntimeException e) {
            Log.warnf(e, "Keycloak invitation mail could not be sent to %s; the account exists", email);
        }
    }

    /**
     * Invited now counts as verified, as for an account created by a save:
     * the next round does not mail the same person again.
     */
    private void markInvited(RealmResource realm, String userId) {
        UserResource utilisateur = realm.users().get(userId);
        UserRepresentation representation = utilisateur.toRepresentation();
        representation.setEmailVerified(true);
        utilisateur.update(representation);
    }

    private void sendInvitation(RealmResource realm, String userId) {
        UserResource utilisateur = realm.users().get(userId);
        utilisateur.executeActionsEmail(config.provisioning().invitationActions());
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
