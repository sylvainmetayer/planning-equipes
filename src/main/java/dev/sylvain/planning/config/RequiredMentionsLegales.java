package dev.sylvain.planning.config;

import io.quarkus.logging.Log;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Refuses to boot a live <b>public</b> deployment whose legal notice page would be
 * empty, and warns on any other.
 *
 * <p>{@code /api/mentions-legales} is public and always reachable, and the
 * page renders "Aucune information légale n'a été renseignée sur ce
 * déploiement" when nothing was given. That sentence is the honest answer on a
 * demo instance and a defect on an instance in service: the LCEN wants an
 * identified publisher and host, and the GDPR a legal basis, a retention
 * period and an address to exercise one's rights.</p>
 *
 * <p>The asymmetry this class removes: {@link DefaultSecrets} already refuses
 * the boot on a password left at its example value, while the seven
 * legal-notice settings (the keys of {@code LEGAL_MENTIONS}) — listed as a pre-opening checkbox in
 * {@code site/src/docs/installer/securite.md} — were silently optional. A checklist nobody ticks
 * protects nothing, and this is the one item on it a boot check can hold.</p>
 *
 * <p>Five of the seven are required, and the other two are deliberately not.
 * {@code responsable-traitement} falls back to the publisher by design, so
 * demanding it separately would contradict its own fallback; and a directeur
 * de publication is not required of every publisher. Both stay optional.</p>
 *
 * <p><b>Only a public instance is refused</b> ({@code PUBLIC_INSTANCE}, ADR
 * 0110): anywhere else the same sentence is logged as a warning and the boot
 * goes on, because an organiser's own instance, reachable by its team alone,
 * has no stranger to publish a notice to.</p>
 *
 * <p>The escape hatch is explicit rather than inferred: a demo or test
 * instance sets {@code LEGAL_DEMO_INSTANCE=true} and boots with an empty page.
 * Guessing that from {@code PUBLIC_URL} or from the launch mode would have
 * been shorter and wrong — a demo instance is a decision about what an
 * instance is <em>for</em>, which nothing in the configuration reveals.</p>
 *
 * <p>Read as a boot check rather than as validation on the
 * {@link ConfigMentionsLegales} mapping, like {@link DefaultSecrets}: what
 * matters is not the shape of each value but that somebody wrote them.</p>
 */
@ApplicationScoped
public class RequiredMentionsLegales {

    private final ConfigMentionsLegales mentions;

    private final boolean publicInstance;

    @Inject
    public RequiredMentionsLegales(
            ConfigMentionsLegales mentions,
            @ConfigProperty(name = "planning.public-instance", defaultValue = "false") boolean publicInstance) {
        this.mentions = mentions;
        this.publicInstance = publicInstance;
    }

    void checkAtStartup(@Observes StartupEvent startup) {
        // Dev and test run on the shipped empty defaults on purpose, exactly
        // as they do for the two passwords.
        if (LaunchMode.current().isProduction()) {
            check(
                    mentions.demoInstance(),
                    publicInstance,
                    value(mentions.editeur()),
                    value(mentions.hebergeur()),
                    value(mentions.contact()),
                    value(mentions.donnees().baseLegale()),
                    value(mentions.donnees().conservation()));
        }
    }

    private static String value(Optional<String> configured) {
        return configured.orElse("");
    }

    /**
     * Package-private and static so the rule can be unit-tested without a
     * production boot, like {@link DefaultSecrets#check}. Takes the values
     * rather than the mapping for the same reason: the rule is about what an
     * operator wrote, and a test should not have to stand in for Quarkus.
     */
    static void check(
            boolean demoInstance,
            boolean publicInstance,
            String editeur,
            String hebergeur,
            String contact,
            String baseLegale,
            String conservation) {
        if (demoInstance) {
            return;
        }
        Map<String, String> required = new LinkedHashMap<>();
        required.put("editeur", editeur);
        required.put("hebergeur", hebergeur);
        required.put("contact", contact);
        required.put("base-legale", baseLegale);
        required.put("conservation", conservation);

        List<String> missing = new ArrayList<>();
        required.forEach((key, written) -> {
            // Blank counts as missing: the page renders the very same "not
            // provided" case for a space as for an unset variable.
            if (written == null || written.isBlank()) {
                missing.add(key);
            }
        });
        if (missing.isEmpty()) {
            return;
        }
        // Every one of them at once: an operator who fixes one variable per
        // boot pays five restarts to learn what a single message can say.
        String message = "La page /mentions-legales est publique et serait vide : "
                + "LEGAL_MENTIONS ne renseigne pas " + String.join(", ", missing) + ". "
                + "La LCEN exige un éditeur et un hébergeur identifiés, le RGPD une base légale, "
                + "une durée de conservation et une adresse pour exercer ses droits. ";
        if (publicInstance) {
            throw new IllegalStateException(
                    message
                            + "Renseignez-les, ou posez LEGAL_DEMO_INSTANCE=true s'il ne s'agit pas d'une instance en service.");
        }
        // Not public: the operator is told, never blocked (ADR 0110).
        Log.warn(message + "Renseignez-les avant d'ouvrir l'instance au public : avec PUBLIC_INSTANCE=true, "
                + "le démarrage serait refusé.");
    }
}
