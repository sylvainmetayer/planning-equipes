package dev.sylvain.planning.service;

/** The one rule every referential shares: a business id is client-supplied and must not be blank. */
final class Identifiants {

    private Identifiants() {
    }

    static String requis(String id, String nomDuChamp) {
        if (id == null || id.isBlank()) {
            throw new ErreurMetier.Invalide("Missing " + nomDuChamp);
        }
        return id;
    }
}
