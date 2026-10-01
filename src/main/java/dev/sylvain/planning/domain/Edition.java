package dev.sylvain.planning.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * A whole edition of the event — "Année 2025", "Année 2026" — and the scope
 * every piece of reference data belongs to: stands, animateurs, typologies,
 * emplacements, timeslot groups, ad hoc constraints, parameters and the
 * persisted solve. Two editions never see each other's rows, so a past edition
 * stays readable while the next one is being prepared.
 *
 * <p>A fallback plan is a dated consigne inside the live edition (ADR 0043),
 * not a duplicated edition. See {@code docs/decisions/0001-cloisonnement-par-edition.md}
 * and {@code docs/decisions/0072-une-seule-edition-active.md}.</p>
 *
 * <p>{@code active} is not "the current edition" — that one is designated by
 * the client on every request through the {@code X-Edition-Id} header. It is
 * the one edition allowed to reach outside: publish, send mail, open the
 * animateur espace, the ICS feed and the wall display. At most one edition is
 * active; none is a valid state between two events.</p>
 */
public class Edition {

    private String id;
    private String nom;
    private boolean active;
    private Instant creeLe;

    public Edition() {}

    public Edition(String id, String nom, boolean active, Instant creeLe) {
        this.id = id;
        this.nom = nom;
        this.active = active;
        this.creeLe = creeLe;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getNom() {
        return nom;
    }

    public void setNom(String nom) {
        this.nom = nom;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public Instant getCreeLe() {
        return creeLe;
    }

    public void setCreeLe(Instant creeLe) {
        this.creeLe = creeLe;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Edition edition)) {
            return false;
        }
        return Objects.equals(id, edition.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
