package dev.sylvain.planning.api;

import dev.sylvain.planning.service.BusinessError;

/**
 * A request body its route cannot do without. An empty body, or a JSON
 * {@code null}, binds to {@code null}: refused here as the bad request it is,
 * rather than as the 500 of its first dereference.
 */
final class RequiredBody {

    static final String MESSAGE = "Le corps de la requête est obligatoire.";

    private RequiredBody() {}

    static void require(Object body) {
        if (body == null) {
            throw new BusinessError.Invalid(MESSAGE);
        }
    }
}
