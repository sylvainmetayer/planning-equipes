package dev.sylvain.planning.service.publication;

import java.util.Optional;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * The address the organisation is reached at, and the single reader of
 * {@code planning.mail.admin}. Empty when the property is unset or blank — the
 * documented way to switch every admin notification off, which is why callers
 * treat "no address" as "say nothing" rather than as a misconfiguration.
 */
@ApplicationScoped
public class AdminAddress {

    private final Optional<String> resolue;

    /**
     * @param configuree {@link Optional} and not {@code String}: MicroProfile
     *                   Config treats a blank value as an absent property, so
     *                   {@code MAIL_ADMIN=} — the documented way to disable
     *                   the notifications — would fail a mandatory injection
     */
    @Inject
    public AdminAddress(@ConfigProperty(name = "planning.mail.admin") Optional<String> configuree) {
        this.resolue = configuree == null
                ? Optional.empty()
                : configuree.map(String::trim).filter(address -> !address.isBlank());
    }

    /** The configured address, trimmed, or empty when notifications are disabled. */
    public Optional<String> resolue() {
        return resolue;
    }
}
