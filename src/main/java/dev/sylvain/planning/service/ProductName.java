package dev.sylvain.planning.service;

import dev.sylvain.planning.config.ConfigBranding;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * The name this deployment answers to, and the single reader of
 * {@code planning.branding.product-name} in the service layer.
 *
 * <p>It exists for the same reason {@link AdminAddress} does: a value read from
 * the configuration in five places is a value that ends up trimmed in four of
 * them and not in the fifth. Every mail subject, the SQL dump header and the
 * calendar producer id go through here, so they cannot drift apart.</p>
 *
 * <p>Never blank: an empty product name would produce subjects starting with a
 * dash, so it falls back to the neutral default rather than to nothing.</p>
 */
@ApplicationScoped
public class ProductName {

    private static final String NEUTRAL = "Planning Équipes";

    private final String value;

    @Inject
    public ProductName(ConfigBranding branding) {
        this(branding.productName());
    }

    /** Direct constructor, for the callers that live outside CDI (the service tests). */
    public ProductName(String value) {
        String trimmed = value == null ? "" : value.trim();
        this.value = trimmed.isEmpty() ? NEUTRAL : trimmed;
    }

    /** Neutral default, for the callers that have no configuration at hand. */
    public static ProductName neutral() {
        return new ProductName(NEUTRAL);
    }

    public String value() {
        return value;
    }

    /**
     * A mail subject: the product first, then what the mail is about. Every
     * subject of the application is built here so an inbox groups them
     * together.
     */
    public String subject(String about) {
        return value + " — " + about;
    }
}
