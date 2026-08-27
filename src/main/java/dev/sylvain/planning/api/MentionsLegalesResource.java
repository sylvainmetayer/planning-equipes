package dev.sylvain.planning.api;

import jakarta.inject.Inject;
import dev.sylvain.planning.config.ConfigMentionsLegales;
import dev.sylvain.planning.config.ConfigObservabilite;
import java.util.Optional;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Deployment-specific facts of the legal notice — who publishes the site, who
 * hosts it, whom to write to, how long personal data is kept.
 *
 * <p>They live in environment variables rather than in the code for two
 * reasons. This repository is public, and for a publisher who is a natural
 * person the postal address and registration number <em>are</em> personal
 * data: they have no business in a Git history. And another organisation
 * deploying this application must be able to state its own identity without
 * touching the source.</p>
 *
 * <p>Every field is optional and blank by default. The page renders what it
 * was given and says plainly what is missing — a legal notice that invented a
 * publisher would be worse than one that admits it has not been filled in.</p>
 *
 * <p>Public like {@code /api/config}: this page must stay readable by someone
 * who is not logged in, and by an animateur whose access link has expired —
 * exactly the reader who needs to know whom to contact.</p>
 *
 * <p>It also carries which third-party tools this deployment actually runs.
 * The privacy notice used to name Cloudflare and Bugsink unconditionally,
 * so a deployment with both switched off published two processings it does
 * not perform, one of them a transfer outside the EU. Reading that from
 * {@code /api/config} instead would have been shorter and wrong: its fetch
 * fails silently into a disabled configuration, which would <em>hide</em>
 * the paragraphs on a deployment where both tools run — the one error that
 * costs. A failure here shows the page's error message instead.</p>
 */
@Path("/mentions-legales")
@Produces(MediaType.APPLICATION_JSON)
public class MentionsLegalesResource {

    @Inject
    ConfigMentionsLegales mentions;

    @Inject
    ConfigObservabilite observabilite;

    @GET
    public MentionsLegalesView get() {
        return new MentionsLegalesView(
                text(mentions.editeur()),
                text(mentions.directeurPublication()),
                text(mentions.hebergeur()),
                text(mentions.contact()),
                text(mentions.responsableTraitement()),
                text(mentions.donnees().baseLegale()),
                text(mentions.donnees().conservation()),
                configured(observabilite.cloudflare().webAnalyticsToken()),
                configured(observabilite.sentry().dsn()));
    }

    /** Trimmed, and empty rather than blank: the UI has one single "not filled in" case to handle. */
    private static String text(Optional<String> valeur) {
        return valeur.map(String::trim).filter(v -> !v.isEmpty()).orElse("");
    }

    /**
     * Whether a token or DSN is set at all, tested exactly as the tools test
     * it — untrimmed, unlike {@link #text}. {@code /api/config} hands the
     * value over as it stands, so {@code observability.ts} loads the beacon
     * and the error SDK on anything non-empty, and {@code SentryInitializer}
     * initializes the SDK on any present value. Trimming here would answer
     * "no tool" for a whitespace-only DSN while both SDKs run, and the page
     * would hide a processing that takes place — the one error that costs.
     * The values themselves stay out of this answer: they are already public
     * on {@code /api/config}, and the page needs to know that a tool runs,
     * not how to reach it.
     */
    private static boolean configured(Optional<String> valeur) {
        return !valeur.orElse("").isEmpty();
    }

    /**
     * Empty string means "not configured on this deployment", and the page says
     * so where the value should have been.
     *
     * @param editeur              publisher: name, legal form, address, registration number
     * @param directeurPublication person responsible for publication
     * @param hebergeur            host: name and address
     * @param contact              address to reach the publisher, and to exercise one's rights
     * @param responsableTraitement data controller when it differs from the publisher
     * @param baseLegale           legal basis of the processing, in the deployment's own terms
     * @param conservation         how long personal data is kept
     * @param mesureAudience       whether Cloudflare Web Analytics runs on this deployment
     * @param suiviErreurs         whether error reports are sent to a Sentry-protocol endpoint
     */
    public record MentionsLegalesView(
            String editeur,
            String directeurPublication,
            String hebergeur,
            String contact,
            String responsableTraitement,
            String baseLegale,
            String conservation,
            boolean mesureAudience,
            boolean suiviErreurs) {
    }
}
