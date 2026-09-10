package dev.sylvain.planning.service.referentiel;

import java.util.List;
import java.util.Map;
import java.util.Set;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.ContrainteAdHoc;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.Emplacement;
import dev.sylvain.planning.domain.ParametresDecoupage;
import dev.sylvain.planning.domain.ParametresLegaux;
import dev.sylvain.planning.domain.ParametresSolveur;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.domain.VerrouillagePlanning;

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

    List<Creneau> listCreneaux();

    List<Emplacement> listEmplacements();

    List<TypologieItem> listTypologies();

    List<VerrouillagePlanning> listVerrouillages();

    List<ContrainteAdHoc> snapshotContraintes();

    Set<String> getContraintesDesactivees();

    /**
     * Per-constraint weights this edition overrides, by constraint name. What
     * is absent keeps the deployment default configured in
     * {@code application.properties}.
     */
    Map<String, Integer> getConstraintWeights();

    ParametresLegaux getParametresLegaux();

    ParametresDecoupage getParametresDecoupage();

    ParametresSolveur getParametresSolveur();

    /**
     * Persists the relay families a build assigned (issue #390). A harness
     * without a database has nothing to record.
     */
    default void recordStandFamilies(java.util.List<Stand> stands, java.util.Map<String, Integer> familleParStand) {
    }
}
