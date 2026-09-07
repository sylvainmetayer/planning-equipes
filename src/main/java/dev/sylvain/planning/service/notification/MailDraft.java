package dev.sylvain.planning.service.notification;

/**
 * A written notification, ready to hand to a transport. French, written for
 * the animateur — business words, no technical vocabulary. {@code corps} is
 * the plain-text part, the one a test reads; {@code html} the alternative a
 * mail client shows when it can, built on the shared layout.
 *
 * <p>Separating "what to say" from "how to deliver it" is what lets
 * {@link NotificationWriter} be tested without a {@code Mailer}, an SMTP
 * server or a Quarkus context: every wording assertion runs against this
 * record.</p>
 */
public record MailDraft(String destinataire, String sujet, String corps, String html) {
}
