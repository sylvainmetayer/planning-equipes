package dev.sylvain.planning.api;

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
 */
@Path("/mentions-legales")
@Produces(MediaType.APPLICATION_JSON)
public class MentionsLegalesResource {

    // Optional<String>, not String: SmallRye turns a blank value into null,
    // which fails startup validation on a plain String injection point (same
    // reasoning as ConfigResource).
    @ConfigProperty(name = "planning.legal.editeur")
    Optional<String> editeur;

    @ConfigProperty(name = "planning.legal.directeur-publication")
    Optional<String> directeurPublication;

    @ConfigProperty(name = "planning.legal.hebergeur")
    Optional<String> hebergeur;

    @ConfigProperty(name = "planning.legal.contact")
    Optional<String> contact;

    @ConfigProperty(name = "planning.legal.donnees.base-legale")
    Optional<String> baseLegale;

    @ConfigProperty(name = "planning.legal.donnees.conservation")
    Optional<String> conservation;

    @GET
    public MentionsLegalesView get() {
        return new MentionsLegalesView(
                texte(editeur),
                texte(directeurPublication),
                texte(hebergeur),
                texte(contact),
                texte(baseLegale),
                texte(conservation));
    }

    /** Trimmed, and empty rather than blank: the UI has one single "not filled in" case to handle. */
    private static String texte(Optional<String> valeur) {
        return valeur.map(String::trim).filter(v -> !v.isEmpty()).orElse("");
    }

    /**
     * Empty string means "not configured on this deployment", and the page says
     * so where the value should have been.
     *
     * @param editeur              publisher: name, legal form, address, registration number
     * @param directeurPublication person responsible for publication
     * @param hebergeur            host: name and address
     * @param contact              address to reach the publisher, and to exercise one's rights
     * @param baseLegale           legal basis of the processing, in the deployment's own terms
     * @param conservation         how long personal data is kept
     */
    public record MentionsLegalesView(
            String editeur,
            String directeurPublication,
            String hebergeur,
            String contact,
            String baseLegale,
            String conservation) {
    }
}
