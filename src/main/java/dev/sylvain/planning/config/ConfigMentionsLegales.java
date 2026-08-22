package dev.sylvain.planning.config;

import java.util.Optional;

import io.smallrye.config.ConfigMapping;

/**
 * Everything the legal notices page states about who runs this instance.
 * All optional: a deployment that fills nothing simply shows nothing, which is
 * the honest answer for a local or test instance.
 */
@ConfigMapping(prefix = "planning.legal")
public interface ConfigMentionsLegales {

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
