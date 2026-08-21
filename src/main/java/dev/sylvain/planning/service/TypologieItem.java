package dev.sylvain.planning.service;

/**
 * A referential typologie. {@code ninja} marks the single typologie whose
 * holders are considered versatile: they are eligible for any stand and are
 * the pool the "buffer de polyvalents" soft constraint keeps some slack on.
 * At most one typologie of the referential carries the flag — {@link
 * TypologieService} clears the previous one on save, and a partial unique
 * index (V33, scoped by edition) backs the rule up in the database.
 */
public record TypologieItem(String id, String label, boolean ninja) {

    public TypologieItem {
        if (label == null || label.isBlank()) {
            label = id;
        }
    }

    public TypologieItem(String id, String label) {
        this(id, label, false);
    }
}
