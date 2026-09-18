package dev.sylvain.planning.service.edition;

import dev.sylvain.planning.domain.Edition;
import dev.sylvain.planning.service.referentiel.JoursEvenement;
import dev.sylvain.planning.service.referentiel.ReferenceDataService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Reads the label of the edition the current request works in (issue #608):
 * its name from the edition itself, its span from the créneaux it holds.
 *
 * <p>No lookup is allowed to sink the page or the document that asks for it:
 * an edition that cannot be resolved leaves the label unsaid — the espace
 * shows a planning without a title rather than an error, exactly as it did
 * before the title existed.</p>
 */
@ApplicationScoped
public class EtiquetteEditionService {

    @Inject
    EditionService editions;

    @Inject
    ReferenceDataService referenceData;

    /** The current edition's name and span, {@link EtiquetteEdition#INCONNUE} when it cannot be read. */
    public EtiquetteEdition courante() {
        try {
            Edition edition = editions.editionCourante();
            JoursEvenement jours = JoursEvenement.of(referenceData.listCreneaux());
            return new EtiquetteEdition(edition == null ? null : edition.getNom(), jours.first(), jours.last());
        } catch (RuntimeException e) {
            return EtiquetteEdition.INCONNUE;
        }
    }
}
