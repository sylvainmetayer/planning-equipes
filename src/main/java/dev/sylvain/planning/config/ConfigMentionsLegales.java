package dev.sylvain.planning.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.util.Optional;

/**
 * Everything the legal notices page states about who runs this instance.
 * All optional: a deployment that fills nothing simply shows nothing, which is
 * the honest answer for a local or test instance.
 */
@ConfigMapping(prefix = "planning.legal")
public interface ConfigMentionsLegales {

    /**
     * Declares this instance a demo or a test bench, which is the one case
     * where an empty legal notice is the honest answer rather than a defect.
     * Never rendered: it says what the instance is for, not what the page
     * states. {@link RequiredMentionsLegales} is the only reader.
     */
    @WithDefault("false")
    boolean demoInstance();

    Optional<String> editeur();

    Optional<String> directeurPublication();

    Optional<String> hebergeur();

    Optional<String> contact();

    /**
     * Data controller, when it is not the publisher. "Éditeur" belongs to the
     * LCEN, "responsable de traitement" to the GDPR: usually the same body,
     * not necessarily. Blank falls back to the publisher.
     */
    Optional<String> responsableTraitement();

    Data donnees();

    /** What the privacy notice says about the personal data held here. */
    interface Data {

        Optional<String> baseLegale();

        Optional<String> conservation();
    }
}
