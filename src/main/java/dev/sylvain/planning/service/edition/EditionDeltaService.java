package dev.sylvain.planning.service.edition;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.ContrainteAdHoc;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.Edition;
import dev.sylvain.planning.domain.ParametresLegaux;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.domain.TypeContrainteAdHoc;
import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.EditionContext;
import dev.sylvain.planning.service.edition.EditionDelta.DeltaSide;
import dev.sylvain.planning.service.edition.EditionDelta.DeltaVolumes;
import dev.sylvain.planning.service.referentiel.ParametresService;
import dev.sylvain.planning.service.referentiel.ReferenceDataService;
import dev.sylvain.planning.service.solve.ProblemBuilder;
import dev.sylvain.planning.service.solve.ProblemScaleService.ProblemScale;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The referential delta between two editions (see {@link EditionDelta}): what
 * changed in the stands, animateurs, timeslots and staffing from last year's
 * edition to this one, or from an edition to its variant.
 *
 * <p>Each edition is read <b>inside</b> it, through
 * {@link EditionContext#executeIn} and the services every screen already
 * reads — no statement spans two editions, so the partitioning holds by
 * construction and {@code IsolationEditionStructurelleTest} has nothing new
 * to look at. The comparison itself is {@link EditionDeltaComparator}, pure.</p>
 *
 * <p>Read-only, and never waits for a solve: a solve running in either
 * edition changes its plan, not the referential read here.</p>
 *
 * <p><b>Anonymous by default.</b> {@link #compare} names no animateur — it is
 * what the assistant and the CSV are handed; the administrator's screen alone
 * asks for the names, through {@link #compareNamingAnimateurs}, whose name says
 * what it carries.</p>
 */
@ApplicationScoped
public class EditionDeltaService {

    private final EditionService editionService;

    private final EditionContext editionContext;

    private final ReferenceDataService referenceData;

    private final ParametresService parametres;

    @Inject
    public EditionDeltaService(
            EditionService editionService,
            EditionContext editionContext,
            ReferenceDataService referenceData,
            ParametresService parametres) {
        this.editionService = editionService;
        this.editionContext = editionContext;
        this.referenceData = referenceData;
        this.parametres = parametres;
    }

    /**
     * The delta with no animateur named: an animateur line carries its ids
     * and the names of the fields that differ, never a label.
     *
     * @throws BusinessError.NotFound when either id names no edition — both
     *         are path segments of the routes calling this
     */
    public EditionDelta compare(String referenceId, String targetId) {
        return compareNamingAnimateurs(referenceId, targetId).withoutPersonNames();
    }

    /**
     * The same delta with every animateur line labelled with the person's
     * name — for the administrator's screen, and nothing else.
     *
     * @throws BusinessError.NotFound when either id names no edition
     */
    public EditionDelta compareNamingAnimateurs(String referenceId, String targetId) {
        DeltaSide reference = side(referenceId);
        DeltaSide target = side(targetId);
        return EditionDeltaComparator.compare(load(reference), load(target), parametres::configuredWeight);
    }

    /** The delta as a CSV: ids, codes and counts, never an animateur's name or birth date. */
    public String csv(String referenceId, String targetId) {
        return EditionDeltaCsv.write(compare(referenceId, targetId));
    }

    private DeltaSide side(String id) {
        return editionService.listEditions().stream()
                .filter(edition -> edition.getId().equals(id))
                .findFirst()
                .map(edition -> new DeltaSide(edition.getId(), name(edition)))
                .orElseThrow(() -> new BusinessError.NotFound("Édition introuvable : " + id));
    }

    private static String name(Edition edition) {
        return edition.getNom() == null ? edition.getId() : edition.getNom();
    }

    private EditionDeltaComparator.Side load(DeltaSide edition) {
        return editionContext.executeIn(edition.id(), () -> {
            List<Stand> stands = referenceData.listStands();
            List<Animateur> animateurs = referenceData.listAnimateurs();
            List<Creneau> creneaux = referenceData.listCreneaux();
            List<ContrainteAdHoc> contraintes = referenceData.listContraintesAdHoc();
            ParametresLegaux legaux = referenceData.getParametresLegaux();
            // The Volumétrie of ProblemScaleService.compute, on the lists read
            // once here: resolving the windows sets the effective ones only,
            // never the dated rows the delta compares.
            referenceData.resolveHoraires(stands, creneaux);
            ProblemScale scale = ProblemScale.of(
                    ProblemBuilder.buildPostes(stands, creneaux), animateurs, contraintes.size(), legaux);
            Map<TypeContrainteAdHoc, Integer> ajustements = new EnumMap<>(TypeContrainteAdHoc.class);
            for (ContrainteAdHoc contrainte : contraintes) {
                if (contrainte.getType() != null) {
                    ajustements.merge(contrainte.getType(), 1, Integer::sum);
                }
            }
            return new EditionDeltaComparator.Side(
                    edition,
                    referenceData.listTypologies(),
                    referenceData.listEmplacements(),
                    stands,
                    animateurs,
                    creneaux,
                    referenceData.listJourneesTypes(),
                    legaux,
                    referenceData.getEtatsContraintes(),
                    referenceData.getConstraintWeights(),
                    ajustements,
                    new DeltaVolumes(
                            scale.animateurCount(),
                            scale.posteCount(),
                            scale.hoursToFill(),
                            scale.hoursAvailable(),
                            EditionDeltaComparator.fillRatio(scale.hoursToFill(), scale.hoursAvailable())));
        });
    }
}
