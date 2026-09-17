package dev.sylvain.planning.domain;

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

    public QuotaTypologie() {}

    public QuotaTypologie(String typologie, int maxCreneaux) {
        this.typologie = typologie;
        this.maxCreneaux = maxCreneaux;
    }

    public String getTypologie() {
        return typologie;
    }

    public void setTypologie(String typologie) {
        this.typologie = typologie;
    }

    public int getMaxCreneaux() {
        return maxCreneaux;
    }

    public void setMaxCreneaux(int maxCreneaux) {
        this.maxCreneaux = maxCreneaux;
    }
}
