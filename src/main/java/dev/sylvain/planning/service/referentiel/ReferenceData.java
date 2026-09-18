package dev.sylvain.planning.service.referentiel;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.ConsigneEdition;
import dev.sylvain.planning.domain.ContrainteAdHoc;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.Emplacement;
import dev.sylvain.planning.domain.FenetreRepas;
import dev.sylvain.planning.domain.JourneeType;
import dev.sylvain.planning.domain.ParametresLegaux;
import dev.sylvain.planning.domain.ParametresQualite;
import dev.sylvain.planning.domain.ParametresSolveur;
import dev.sylvain.planning.domain.PrereglageConsigne;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.domain.VerrouillagePlanning;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Everything {@link PlanningService} needs to read to build a problem — and
 * nothing else. {@link ReferenceDataService} is the implementation in
 * production.
 *
 * <p>This interface exists so that the plain (non-CDI) tests can hand the
 * solver an <b>empty</b> referential without production code carrying a
 * null-object for their benefit. Before it, every read method of
 * {@code ReferenceDataService} opened with {@code repository == null ? …} —
 * fifteen branches that were unreachable in production and that quietly
 * turned a genuine wiring failure into an empty list.</p>
 */
public interface ReferenceData {

    List<Animateur> listAnimateurs();

    List<Stand> listStands();

    /** Stands with their horaire rules expanded against the edition's days. */
    List<Stand> listSolvedStands();

    /**
     * Expands {@code stands}' schedules against {@code creneaux} — recurring
     * rules, dated exceptions, then the edition's consignes (issue #4). The
     * one entry point for a caller that reads its own lists: expanding the
     * rules by hand would silently ignore a consigne and staff a band an
     * arrêté closed.
     */
    void resolveHoraires(List<Stand> stands, List<Creneau> creneaux);

    List<Creneau> listCreneaux();

    List<Emplacement> listEmplacements();

    List<TypologieItem> listTypologies();

    List<VerrouillagePlanning> listVerrouillages();

    List<ContrainteAdHoc> snapshotContraintes();

    /**
     * The organisational-quality thresholds this edition solves with: its own
     * row, or the deployment's {@code planning.contraintes.*} defaults while it
     * has none.
     */
    ParametresQualite getParametresQualite();

    /**
     * Every constraint the next solve will not enforce — the ones switched off
     * here, and the ones the catalogue ships off. Callers that only need to
     * know what applies read this one.
     */
    Set<String> getContraintesDesactivees();

    /**
     * The state this edition explicitly chose, per constraint name; a name
     * absent from the map follows the catalogue default. This is what the
     * solver is handed as {@code ConstraintToggle} facts, so a problem built
     * without any of them still behaves like an untouched edition.
     */
    Map<String, Boolean> getEtatsContraintes();

    /**
     * Per-constraint weights this edition overrides, by constraint name. What
     * is absent keeps the deployment default configured in
     * {@code application.properties}.
     */
    Map<String, Integer> getConstraintWeights();

    ParametresLegaux getParametresLegaux();

    /**
     * The meal windows a solve and every read-out honour: the edition's, and
     * on a date under a consigne that restates them (issue #4), that
     * consigne's, dated. The one place the two are merged — a caller building
     * the facts from the parameters alone would read the edition's rule on a
     * day the organiser said otherwise.
     */
    List<FenetreRepas> fenetresRepas();

    ParametresSolveur getParametresSolveur();

    /** The day templates (ADR 0032); none by default, for the readers that predate them. */
    default List<JourneeType> listJourneesTypes() {
        return List.of();
    }

    /** Which date each template governs; empty when the edition assigns none. */
    default List<JourneesTypesMaterialisation.Affectation> calendrierJourneesTypes() {
        return List.of();
    }

    /** The edition's consignes (ADR 0043), one per date; none by default, for the readers that predate them. */
    default List<ConsigneEdition> listConsignes() {
        return List.of();
    }

    /** The presets those consignes are made from; none by default. */
    default List<PrereglageConsigne> listPrereglagesConsigne() {
        return List.of();
    }
}
