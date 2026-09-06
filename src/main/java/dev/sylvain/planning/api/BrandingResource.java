package dev.sylvain.planning.api;

import java.util.Optional;

import dev.sylvain.planning.config.ConfigBranding;
import dev.sylvain.planning.service.ProductName;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * The brand this deployment wears, as the browser needs it: the product name
 * to put in the tab, the logo to show in the toolbars, the accent colour to
 * paint with.
 *
 * <p>Public, and for the same reason as {@code /api/mentions-legales}: the
 * login page and the espace animateur are read by people who hold no admin
 * session, and an application that could only tell you its own name once you
 * were logged in would show a blank toolbar to exactly the visitors it is
 * meant to greet.</p>
 *
 * <p>Read once before the Angular application bootstraps, like
 * {@code /api/config} — the title and the logo decide what the very first
 * frame looks like, and a screen cannot wait for a round trip to know what it
 * is called.</p>
 */
@Path("/branding")
@Produces(MediaType.APPLICATION_JSON)
public class BrandingResource {

    @Inject
    ConfigBranding branding;

    /** Same reader as the mail subjects and the PDFs: one fallback, not two. */
    @Inject
    ProductName productName;

    @GET
    public BrandingView get() {
        return new BrandingView(
                productName.value(),
                text(branding.organisation()),
                text(branding.logoUrl()),
                text(branding.accentColor()),
                text(branding.mascotUrl()),
                text(branding.mascotIconUrl()),
                text(branding.supportEmail()));
    }

    /** Trimmed, and empty rather than blank: the UI has one single "not configured" case to handle. */
    private static String text(Optional<String> configured) {
        return configured.map(String::trim).filter(v -> !v.isEmpty()).orElse("");
    }

    /**
     * @param productName  name of the product, never empty — it falls back to a
     *                     neutral default rather than to nothing
     * @param organisation customer this instance is deployed for; empty when
     *                     the deployment did not say
     * @param logoUrl      empty means "show no logo", not "show the default one"
     * @param accentColor  empty leaves the compiled Material accent in place
     * @param mascotUrl    empty disables the mascot easter egg
     * @param mascotIconUrl empty falls back to a Material icon while a solve runs
     * @param supportEmail  address the help page's « Contact et support » names;
     *                      empty hides that paragraph and its link
     */
    public record BrandingView(
            String productName,
            String organisation,
            String logoUrl,
            String accentColor,
            String mascotUrl,
            String mascotIconUrl,
            String supportEmail) {
    }
}
