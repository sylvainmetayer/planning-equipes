package dev.sylvain.planning.service.notification;

/**
 * A written notification, ready to hand to a transport. Plain text and French,
 * written for the animateur — business words, no technical vocabulary.
 *
 * <p>Separating "what to say" from "how to deliver it" is what lets
 * {@link RedacteurNotifications} be tested without a {@code Mailer}, an SMTP
 * server or a Quarkus context: every wording assertion runs against this
 * record.</p>
 */
public record Courrier(String destinataire, String sujet, String corps) {
}
