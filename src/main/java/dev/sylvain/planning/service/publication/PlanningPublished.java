package dev.sylvain.planning.service.publication;

/**
 * A plan was just published: fired by {@code PlanPublicationService.publier}
 * once the snapshot, the recipients and the markers are written.
 *
 * <p>A CDI event of its own rather than a case of the sealed
 * {@code Notification}: that type is the vocabulary of the mails, and
 * {@code NotificationWriter} owes each of its cases a mail — the publication
 * already is one per recipient. What observes this is the outside world (the
 * webhooks), which needs the counts and nothing nominative.</p>
 *
 * @param snapshotId the published snapshot
 * @param recipients the people this publication addressed
 * @param sent       mails that left
 * @param withoutEmail addressed people with no address on their fiche
 * @param failed     mails that did not leave
 * @param deferred   people the admin took out of this send
 * @param changed    addressed people whose own planning moved, as read by
 *                   {@code PublicationDiffService}
 */
public record PlanningPublished(
        long snapshotId, int recipients, int sent, int withoutEmail, int failed, int deferred, int changed) {}
