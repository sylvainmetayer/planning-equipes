package dev.sylvain.planning.service.compte;

import java.time.Instant;
import java.util.List;

/**
 * One person able to sign in, whatever the edition (ADR 0049). Never deleted:
 * {@code desactiveLe} closes the account and keeps its history.
 *
 * @param sujet the {@code sub} of the Keycloak token, {@code null} for an
 *              account created in advance that never signed in
 */
public record Compte(
        String id,
        String email,
        String nom,
        String sujet,
        Instant creeLe,
        Instant derniereConnexionLe,
        Instant desactiveLe,
        List<Habilitation> habilitations) {

    public boolean actif() {
        return desactiveLe == null;
    }
}
