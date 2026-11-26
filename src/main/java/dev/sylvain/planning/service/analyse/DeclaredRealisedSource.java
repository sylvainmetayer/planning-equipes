package dev.sylvain.planning.service.analyse;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.service.referentiel.ReferenceDataService;
import dev.sylvain.planning.service.solve.PlanningPersistenceService;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The declared realised: the persisted plan, and the days off the fiches
 * carry, read on the timeslots of those days.
 *
 * <p>The plan of an elapsed day is what the mode jour J left in it — the seats
 * an absence freed, the replacements placed on them — and the past rule (ADR
 * 0044) keeps a later solve from rewriting it. The absences are read the way
 * the mode jour J lists them: every day off of a fiche, whoever wrote it.
 * One added weeks ahead, after the publication, is a known absence all the
 * same; one added before the publication never names a published holder,
 * since the plan the animateurs were sent already respected it.</p>
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
                absences(referenceDataService.listAnimateurs(), referenceDataService.listCreneaux()),
                RealisedNature.DECLARED);
    }

    /** One absence per person and timeslot falling on one of their days off. */
    static Set<SeatAbsence> absences(List<Animateur> animateurs, List<Creneau> creneaux) {
        Set<SeatAbsence> absences = new HashSet<>();
        for (Animateur animateur : animateurs) {
            for (Creneau creneau : creneaux) {
                if (creneau.getId() != null
                        && creneau.getDate() != null
                        && animateur.isIndisponibleOn(creneau.getDate())) {
                    absences.add(new SeatAbsence(animateur.getId(), creneau.getId()));
                }
            }
        }
        return absences;
    }
}
