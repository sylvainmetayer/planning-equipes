package dev.sylvain.planning.service.referentiel;

/**
 * Something the server wants read about a write it <b>accepted</b>.
 *
 * <p>Deliberately <b>not</b> a {@link BusinessError}: that hierarchy carries a
 * refusal status (400/404/409) and its callers undo. A warning has the opposite
 * meaning — the row is written, readable and used by the next solve — so it
 * travels back in the success body, next to the entity, and nothing downstream
 * may turn it into a refusal. The doctrine is the one
 * {@code ContrainteAdHocContradictions} already applies: only what is
 * <em>certainly</em> unsatisfiable is refused, and none of these cases is. An
 * organiser is allowed to record an off day outside the event, a birth date
 * that makes somebody a jeune travailleur, or a timeslot wider than any stand
 * opens.</p>
 *
 * @param type    what was noticed, for a client that wants to act on it rather
 *                than print it
 * @param message the sentence shown to the operator, already naming the dates
 *                and entities involved — a warning nobody can act on is one
 *                they learn to dismiss. It names an animateur by their
 *                <b>id alone</b>, never by nom/prénom nor by date de
 *                naissance: these sentences are read on screens whose client
 *                keeps a log, and an identity has no business ending up there
 *                (see {@code docs/rgpd.md} §7)
 */
public record Avertissement(TypeAvertissement type, String message) {}
