package dev.sylvain.planning.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * Problem fact: how many créneaux one animateur may hold on one typologie of
 * jeu, over the <b>whole edition</b>.
 *
 * <p>The case that asked for it: « les hommes jeu ne peuvent pas faire plus de
 * 4 créneaux sur l'ensemble » (issue #594). Nothing could express a quota
 * before — {@code limiterTypologiesDistinctesParAnimateur} bounds how many
 * different typologies somebody covers, never how much of one, and the
 * work-around was to hand-place forced unavailabilities one by one, on a
 * combination nobody can enumerate in advance.</p>
 *
 * <p>Only the typologies that carry a cap become facts: absence means « no
 * cap », so an edition that sets none hands the solver an empty list and the
 * rule never fires. See ADR 0042 for why the cap sits on the typologie rather
 * than on a fifth kind of ad hoc constraint.</p>
 */
public class QuotaTypologie {

    private String typologie;

    private int maxCreneaux;

    /**
     * The game category's label, for the sentence a broken cap is explained
     * in: its id is a number drawn by the edition (ADR 0050), which tells a
     * reader nothing. Server-side only, never on the wire.
     */
    @JsonIgnore
    private String libelle;

    public QuotaTypologie() {}

    public QuotaTypologie(String typologie, int maxCreneaux) {
        this.typologie = typologie;
        this.maxCreneaux = maxCreneaux;
    }

    public QuotaTypologie(String typologie, String libelle, int maxCreneaux) {
        this(typologie, maxCreneaux);
        this.libelle = libelle;
    }

    public String getTypologie() {
        return typologie;
    }

    public void setTypologie(String typologie) {
        this.typologie = typologie;
    }

    /** The label when known, else the id: never an empty name. */
    @JsonIgnore
    public String getLibelle() {
        return libelle != null ? libelle : typologie;
    }

    public int getMaxCreneaux() {
        return maxCreneaux;
    }

    public void setMaxCreneaux(int maxCreneaux) {
        this.maxCreneaux = maxCreneaux;
    }
}
