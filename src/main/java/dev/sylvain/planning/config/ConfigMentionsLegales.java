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

    Accessibilite accessibilite();

    /** What the privacy notice says about the personal data held here. */
    interface Data {

        Optional<String> baseLegale();

        Optional<String> conservation();
    }

    /**
     * The accessibility statement (article 47 of loi n° 2005-102). The
     * obligation lies on the organisation that deploys this application, not
     * on the repository: it alone knows how far its instance was audited, when,
     * and where users report a barrier.
     */
    interface Accessibilite {

        /** {@code totale}, {@code partielle} or {@code non}; anything else reads as "not stated". */
        Optional<String> etat();

        /** Date of the audit the state rests on, as the deployment writes it. */
        Optional<String> dateAudit();

        /** The contents known not to be accessible, and why. */
        Optional<String> contenusNonAccessibles();

        /** Where to report a barrier; blank falls back to the general contact. */
        Optional<String> signalement();
    }
}
