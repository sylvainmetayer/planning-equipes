package dev.sylvain.planning.domain;

/**
 * Who an animateur calls or writes to about this edition: the organisation's
 * phone number and e-mail address, shown in the espace animateur.
 *
 * <p>Per edition, like every setting of Paramètres › Édition: the organisers of
 * one year are not always the next year's. Both halves are optional — an
 * edition that set neither simply shows no contact — and neither is a
 * person's data the application would own: it is what the organisation chose
 * to publish to its own animateurs.</p>
 *
 * @param telephone the number as the organisation writes it, {@code null} when none
 * @param email     the address, {@code null} when none
 */
public record ContactOrganisation(String telephone, String email) {

    /** Longest phone number accepted: an international number with spaces and an extension. */
    public static final int TELEPHONE_MAX = 40;

    /** RFC 5321's limit on a forward path. */
    public static final int EMAIL_MAX = 254;

    /** What an edition that never set a contact answers. */
    public static ContactOrganisation empty() {
        return new ContactOrganisation(null, null);
    }
}
