package dev.sylvain.planning.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * A whole edition of the festival — "Année 2025", "Année 2026" — and the scope
 * every piece of reference data belongs to: stands, animateurs, typologies,
 * emplacements, timeslot groups, ad hoc constraints, parameters and the
 * persisted solve. Two editions never see each other's rows, so a past edition
 * stays readable while the next one is being prepared.
 *
 * <p>Since issue #172 the edition is the <b>only</b> variant carrier: a
 * "plan canicule" is a duplicated edition, and the découpage replaces the
 * edition's créneaux in place. See {@code docs/editions.md}.</p>
 *
 * <p>{@code defaut} is not "the current edition" — that one is designated by
 * the client on every request through the {@code X-Edition-Id} header. It is
 * the fallback for any caller that designates none.</p>
 */
public class Edition {

    private String id;
    private String nom;
    private boolean defaut;
    private Instant creeLe;

    public Edition() {
    }

    public Edition(String id, String nom, boolean defaut, Instant creeLe) {
        this.id = id;
        this.nom = nom;
        this.defaut = defaut;
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

    public boolean isDefaut() {
        return defaut;
    }

    public void setDefaut(boolean defaut) {
        this.defaut = defaut;
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
