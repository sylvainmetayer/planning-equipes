package dev.sylvain.planning.domain;

import java.time.Instant;
import java.time.LocalDate;

/**
 * « Je ne pourrai pas être là » — an absence an animateur reports from their
 * espace, on a whole day or on one seat. A piece of information, never a write
 * to the referential or to the plan: the organisation observes it and repairs
 * (« Marquer absent et remplacer ») or files it (« Classer »), and until then
 * the animateur can withdraw it.
 *
 * <p>A seat is named by its natural key — timeslot and stand — never by its
 * id: a solve renumbers the seats without moving anybody.</p>
 *
 * @param portee    a whole day, or one seat
 * @param creneauId set for a seat only
 * @param standId   set for a seat only
 * @param motif     optional, from a closed list — never free text
 * @param traiteLe  when the organisation settled it, or when it was withdrawn
 */
public record SignalementAbsence(
        long id,
        String animateurId,
        Portee portee,
        LocalDate jour,
        Long creneauId,
        String standId,
        Motif motif,
        Statut statut,
        Instant signaleLe,
        Instant traiteLe) {

    /** What the report is about. */
    public enum Portee {
        /** The whole day. */
        JOUR,
        /** One seat: a timeslot on a stand. */
        POSTE
    }

    /**
     * Why, when the animateur says — a closed list, so that nothing a person
     * types about their health or their family ever lands in the database.
     */
    public enum Motif {
        PERSONNEL,
        TRANSPORT,
        AUTRE
    }

    /** Where the report stands. */
    public enum Statut {
        /** Reported, waiting for the organisation. */
        SIGNALE,
        /** The organisation marked the absence and freed the seats. */
        TRAITE,
        /** The organisation filed it without touching the plan. */
        CLASSE,
        /** The animateur withdrew it before it was settled. */
        ANNULE
    }

    /** Whether the organisation still has to act on it. */
    public boolean ouvert() {
        return statut == Statut.SIGNALE;
    }
}
