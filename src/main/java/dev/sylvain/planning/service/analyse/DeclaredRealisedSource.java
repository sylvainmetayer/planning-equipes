package dev.sylvain.planning.service.analyse;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.ContrainteAdHoc;
import dev.sylvain.planning.domain.TypeContrainteAdHoc;
import dev.sylvain.planning.service.referentiel.ReferenceDataService;
import dev.sylvain.planning.service.solve.PlanningPersistenceService;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The declared realised: the persisted plan, and the forced unavailabilities
 * recorded on its timeslots.
 *
 * <p>The plan of an elapsed day is what the mode jour J left in it — the seats
 * an absence freed, the replacements placed on them — and the past rule (ADR
 * 0044) keeps a later solve from rewriting it. The absences are read the way
 * the mode jour J lists them: every {@code INDISPONIBILITE_FORCEE} on a
 * timeslot, whoever wrote it. One written weeks ahead from the adjustments
 * screen, after the publication, is a known absence all the same; one written
 * before the publication never names a published holder, since the plan the
 * animateurs were sent already respected it.</p>
 */
@DefaultBean
@ApplicationScoped
public class DeclaredRealisedSource implements RealisedSource {

    private final PlanningPersistenceService persistenceService;

    private final ReferenceDataService referenceDataService;

    @Inject
    public DeclaredRealisedSource(
            PlanningPersistenceService persistenceService, ReferenceDataService referenceDataService) {
        this.persistenceService = persistenceService;
        this.referenceDataService = referenceDataService;
    }

    @Override
    public Realised read() {
        return new Realised(
                persistenceService.loadPersistedPlanning(),
                absences(referenceDataService.listContraintesAdHoc()),
                RealisedNature.DECLARED);
    }

    /** One absence per person and timeslot a forced unavailability names. */
    static Set<SeatAbsence> absences(List<ContrainteAdHoc> contraintes) {
        Set<SeatAbsence> absences = new HashSet<>();
        for (ContrainteAdHoc contrainte : contraintes) {
            if (contrainte.getType() != TypeContrainteAdHoc.INDISPONIBILITE_FORCEE
                    || contrainte.getCreneau() == null
                    || contrainte.getCreneau().getId() == null
                    || contrainte.getAnimateursConcernes() == null) {
                continue;
            }
            for (Animateur cible : contrainte.getAnimateursConcernes()) {
                if (cible != null && cible.getId() != null) {
                    absences.add(new SeatAbsence(
                            cible.getId(),
                            Objects.requireNonNull(contrainte.getCreneau().getId())));
                }
            }
        }
        return absences;
    }
}
