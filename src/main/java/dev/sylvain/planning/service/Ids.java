package dev.sylvain.planning.service;

/**
 * A reference to an existing row must not be blank. Creating one never takes
 * an id from the caller: {@link IdGenerator} draws it (ADR 0050).
 */
public final class Ids {

    private Ids() {}

    public static String required(String id, String nomDuChamp) {
        if (id == null || id.isBlank()) {
            throw new BusinessError.Invalid("Missing " + nomDuChamp);
        }
        return id;
    }
}
