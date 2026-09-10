package dev.sylvain.planning.service.referentiel;

import java.util.Map;

/**
 * The typologie referential seen as what a document needs from it: the label
 * to print for an id. Stands hold typologie ids ({@code AMBIANCE}), animateurs
 * read labels ({@code Jeux d'ambiance}).
 *
 * <p>A narrow interface rather than {@link TypologieService} itself, for the
 * same reason as {@link ReferenceData}: the PDF tests run outside CDI and hand
 * the document a fixed vocabulary, without production code carrying a
 * null-check for their benefit.</p>
 */
public interface TypologieLibelles {

    /** Label of every typologie of the referential, by id. */
    Map<String, String> labelsById();
}
