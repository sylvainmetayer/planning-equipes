package dev.sylvain.planning.api;

/**
 * The error body every refused request answers with: {@code {"message": "…"}},
 * a single sentence written for the person who made the request.
 *
 * <p>It used to be declared <i>inside</i> {@code ReferenceDataResource} and
 * reached as {@code ReferenceDataResource.ErreurValidation} from ten other
 * classes — including two security filters, which had to import a REST
 * resource just to format an error — and re-declared identically in two more.
 * One record, three declarations, nothing tying them together.</p>
 */
public record ErreurValidation(String message) {
}
