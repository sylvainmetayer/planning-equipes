package dev.sylvain.planning.service;

/** The one rule every referential shares: a business id is client-supplied and must not be blank. */
final class Ids {

    private Ids() {
    }

    static String required(String id, String nomDuChamp) {
        if (id == null || id.isBlank()) {
            throw new BusinessError.Invalid("Missing " + nomDuChamp);
        }
        return id;
    }
}
