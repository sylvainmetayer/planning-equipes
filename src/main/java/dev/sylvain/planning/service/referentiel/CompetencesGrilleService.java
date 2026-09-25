package dev.sylvain.planning.service.referentiel;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.journal.CurrentAction;
import dev.sylvain.planning.service.referentiel.GrilleCompetences.LigneCompetences;
import dev.sylvain.planning.service.referentiel.GrilleCompetences.ResultatLigne;
import dev.sylvain.planning.service.referentiel.GrilleCompetences.SaisieCompetences;
import dev.sylvain.planning.service.solve.SolverJobService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The animateur × game-category grid of appreciations, saved from the screen —
 * the only way appreciations are entered in bulk: the grid is not exchanged
 * as a file.
 *
 * <p><b>Saving</b> is one fiche write per submitted row, through the same
 * {@link AnimateurService#update} the form uses — typologies validated, refused
 * while a solve runs, change tracked — with the row's own precondition
 * (issue #362). The rows are independent: a fiche another session wrote
 * meanwhile is refused alone and reported as such, the others are written,
 * and the answer is a line per row rather than one status for the lot. The
 * grid rewrites a fiche's whole map of appreciations, as the form does: a
 * cell emptied on screen is an appreciation removed.</p>
 */
@ApplicationScoped
public class CompetencesGrilleService {

    /** One row per animateur, well under the roster cap: the ceiling a submit is held to. */
    static final int MAX_ROWS = StandGrilleImportService.MAX_ROWS;

    private final AnimateurService animateurs;

    private final SolverJobService solverJobs;

    private final CurrentAction currentAction;

    @Inject
    public CompetencesGrilleService(
            AnimateurService animateurs, SolverJobService solverJobs, CurrentAction currentAction) {
        this.animateurs = animateurs;
        this.solverJobs = solverJobs;
        this.currentAction = currentAction;
    }

    /* ---------------------------------- screen --------------------------------- */

    /**
     * Writes the rows typed in the grid, one fiche each, and says how each one
     * ended. Refused as a whole while a solve runs — the landing persist would
     * revert every row minutes later — and otherwise never as a whole: an
     * unknown animateur, an unknown typologie or a stale fiche costs its own
     * row and nothing else.
     *
     * Two things do cost the whole call, and neither can be answered row by
     * row: a level that is not one of the three names never reaches this method
     * — Jackson refuses the body before it — and a payload longer than the
     * row ceiling is refused outright, since the grid has one row per animateur
     * and nothing legitimate sends more of them.
     */
    @RefusedWhileFrozen(ReferentialFamily.COMPETENCES)
    public List<LigneCompetences> saveGrid(List<SaisieCompetences> saisies) {
        solverJobs.refuseIfSolving();
        if (saisies.size() > MAX_ROWS) {
            throw new BusinessError.Invalid("Trop de lignes envoyées : " + grouped(MAX_ROWS) + " lignes au maximum.");
        }
        Map<String, Animateur> parId = new LinkedHashMap<>();
        animateurs.list().forEach(animateur -> parId.put(animateur.getId(), animateur));
        Set<String> dejaVus = new HashSet<>();
        List<LigneCompetences> lignes = new ArrayList<>();
        boolean modifie = false;
        for (SaisieCompetences saisie : saisies) {
            String id = saisie.animateurId();
            Animateur source = id == null ? null : parId.get(id);
            String refus = gridRejection(id, source, saisie, dejaVus);
            if (refus != null) {
                lignes.add(new LigneCompetences(id, ResultatLigne.REJECTED, refus, null));
            } else {
                LigneCompetences ligne = writeGridRow(id, source, saisie);
                modifie |= ligne.resultat() == ResultatLigne.WRITTEN;
                lignes.add(ligne);
            }
        }
        if (modifie) {
            currentAction.champsModifies(List.of("competences"));
        }
        return lignes;
    }

    /** Why a grid row cannot be written at all, checked in this order; {@code null} when it can. */
    private static String gridRejection(String id, Animateur source, SaisieCompetences saisie, Set<String> dejaVus) {
        if (source == null) {
            return "Animateur inconnu dans la grille : " + id;
        }
        if (!dejaVus.add(id)) {
            return "L'animateur " + id + " apparaît deux fois dans la grille.";
        }
        if (saisie.competences() == null) {
            return "Aucune compétence transmise pour l'animateur " + id + ".";
        }
        return null;
    }

    private LigneCompetences writeGridRow(String id, Animateur source, SaisieCompetences saisie) {
        Animateur copie = GrilleCompetences.withCompetences(source, saisie.competences(), saisie.modifieLe());
        try {
            animateurs.update(id, copie, source);
            return new LigneCompetences(id, ResultatLigne.WRITTEN, null, copie.getModifieLe());
        } catch (BusinessError.Stale stale) {
            return new LigneCompetences(id, ResultatLigne.STALE, stale.getMessage(), stale.getModifieLe());
        } catch (BusinessError refus) {
            return new LigneCompetences(id, ResultatLigne.REJECTED, refus.getMessage(), null);
        }
    }

    /** Thousands spaced out, the way the other caps are written. */
    private static String grouped(int value) {
        return String.format(Locale.ROOT, "%,d", value).replace(',', ' ');
    }
}
