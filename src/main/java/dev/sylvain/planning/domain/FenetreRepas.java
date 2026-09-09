package dev.sylvain.planning.domain;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * One meal window of the event day, as a problem fact: the hours during which
 * a meal break may be taken, and how long that break lasts.
 *
 * <p>The meal break is <b>not</b> the legal break of art. L3121-16, which is
 * twenty minutes owed at the sixth hour and is carried by
 * {@code travailContinuMaxMajeur}. It is the organiser's own rule — the FESTIVAL
 * workbook's midday and evening rotations — and the two are distinct objects:
 * declaring the legal break taken on the post
 * ({@link ParametresLegaux#isPauseSurPoste()}) says nothing about lunch, which
 * is precisely how a ten-hour unbroken day used to pass unnoticed (issue #438).
 *
 * <p>One fact per window rather than a single object carrying both: a rule
 * joins the window it is judging, so a day straddling midday <i>and</i> evening
 * produces one violation per window, each naming its own hours.</p>
 *
 * <p>The values are the ones the organiser already enters on the Découpage
 * screen ({@link ParametresDecoupage}). They were, until issue #438,
 * generation-time only; {@link #depuis(ParametresDecoupage)} is what turns the
 * three that matter into facts the solver reads, whatever the
 * {@link ModeGrilleCreneaux} — a grid entered as vacations is never sliced, and
 * used to escape the rule entirely.</p>
 *
 * @param libelle       « midi » / « soir », for the violation lines and the
 *                      Pauses screen; never a key, only a label
 * @param debut         first instant a meal break may start
 * @param fin           instant it must have ended by
 * @param dureeMinutes  how long the break must last, uninterrupted
 */
public record FenetreRepas(String libelle, LocalTime debut, LocalTime fin, int dureeMinutes) {

    public static final String MIDI = "midi";
    public static final String SOIR = "soir";

    /**
     * The windows of this edition that can actually be honoured, midday first.
     *
     * <p>A window is dropped when it cannot found a rule: a missing bound, an
     * empty or reversed range, a non-positive duration, or a window
     * <i>shorter than the break it requires</i> — nobody could ever satisfy
     * that last one, and a rule no assignment can satisfy would put an edition
     * permanently above zero hard for a reason that is a data entry mistake,
     * not a planning one.</p>
     */
    public static List<FenetreRepas> depuis(ParametresDecoupage parametres) {
        if (parametres == null) {
            return List.of();
        }
        List<FenetreRepas> fenetres = new ArrayList<>(2);
        ajouter(
                fenetres,
                MIDI,
                parametres.getFenetreRepasMidiDebut(),
                parametres.getFenetreRepasMidiFin(),
                parametres.getDureePauseRepasMinutes());
        ajouter(
                fenetres,
                SOIR,
                parametres.getFenetreRepasSoirDebut(),
                parametres.getFenetreRepasSoirFin(),
                parametres.getDureePauseRepasMinutes());
        return List.copyOf(fenetres);
    }

    private static void ajouter(
            List<FenetreRepas> fenetres, String libelle, LocalTime debut, LocalTime fin, int dureeMinutes) {
        if (debut == null || fin == null || !fin.isAfter(debut) || dureeMinutes <= 0) {
            return;
        }
        if (fin.toSecondOfDay() - debut.toSecondOfDay() < dureeMinutes * 60) {
            return;
        }
        fenetres.add(new FenetreRepas(libelle, debut, fin, dureeMinutes));
    }

    /** Minutes from midnight of {@link #debut()} — the unit every window computation works in. */
    public int debutMinutes() {
        return debut.toSecondOfDay() / 60;
    }

    /** Minutes from midnight of {@link #fin()}. */
    public int finMinutes() {
        return fin.toSecondOfDay() / 60;
    }
}
