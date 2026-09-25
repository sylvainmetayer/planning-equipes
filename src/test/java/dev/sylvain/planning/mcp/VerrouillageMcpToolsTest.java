package dev.sylvain.planning.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.sylvain.planning.domain.TypeVerrouillage;
import dev.sylvain.planning.domain.VerrouillagePlanning;
import dev.sylvain.planning.mcp.VerrouillageMcpTools.VerrouillageView;
import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.referentiel.ReferenceDataService;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The two things the view and the deletion owe an assistant: only the target
 * column the type actually uses, and an honest answer on an id that names
 * nothing.
 */
class VerrouillageMcpToolsTest {

    /** Records what was deleted so the test can assert nothing was, on the refusal path. */
    private static final class ReferentielFictif extends ReferenceDataService {

        private final List<VerrouillagePlanning> verrouillages = new ArrayList<>();
        private final List<String> supprimes = new ArrayList<>();

        ReferentielFictif() {
            super(
                    null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                    null, null, null, null);
        }

        @Override
        public List<VerrouillagePlanning> listVerrouillages() {
            return List.copyOf(verrouillages);
        }

        @Override
        public void deleteVerrouillage(String id) {
            supprimes.add(id);
            verrouillages.removeIf(verrouillage -> id.equals(verrouillage.getId()));
        }
    }

    private static VerrouillagePlanning verrouillage(String id, TypeVerrouillage type) {
        VerrouillagePlanning verrouillage = new VerrouillagePlanning(id, type);
        verrouillage.setRaison("validé avec l'équipe");
        return verrouillage;
    }

    @Test
    void theViewCarriesOnlyTheTargetOfItsType() {
        VerrouillagePlanning surJour = verrouillage("V1", TypeVerrouillage.JOUR);
        surJour.setJour(LocalDate.of(2026, 8, 15));

        VerrouillageView view = VerrouillageMcpTools.toView(surJour);

        assertThat(view.type()).isEqualTo("JOUR");
        assertThat(view.jour()).isEqualTo(LocalDate.of(2026, 8, 15));
        assertThat(view.animateurId()).isNull();
        assertThat(view.standId()).isNull();
        assertThat(view.creneauId()).isNull();
        // Free text: that one was written travels, not what it says.
        assertThat(view.raisonRenseignee()).isTrue();
    }

    @Test
    void unVerrouillageSurAnimateurNeDesigneQueSonIdentifiant() {
        VerrouillagePlanning surAnimateur = verrouillage("V2", TypeVerrouillage.ANIMATEUR);
        surAnimateur.setAnimateurId("A-42");

        VerrouillageView view = VerrouillageMcpTools.toView(surAnimateur);

        assertThat(view.animateurId()).isEqualTo("A-42");
        assertThat(view.jour()).isNull();
    }

    @Test
    void unVerrouillageIncompletNeFaitPasEchouerLaLecture() {
        VerrouillageView view = VerrouillageMcpTools.toView(new VerrouillagePlanning());

        assertThat(view.type()).isNull();
        assertThat(view.id()).isNull();
    }

    @Test
    void unlockingAnUnknownIdIsRefusedWithoutDeletingAnything() {
        ReferentielFictif referentiel = new ReferentielFictif();
        referentiel.verrouillages.add(verrouillage("V1", TypeVerrouillage.JOUR));
        VerrouillageMcpTools tools = new VerrouillageMcpTools(referentiel);

        assertThatThrownBy(() -> tools.unlock("V-inexistant", null))
                .isInstanceOf(BusinessError.NotFound.class)
                .hasMessageContaining("V-inexistant");
        assertThat(referentiel.supprimes).isEmpty();
    }

    @Test
    void unlockingAKnownIdDeletesIt() {
        ReferentielFictif referentiel = new ReferentielFictif();
        referentiel.verrouillages.add(verrouillage("V1", TypeVerrouillage.JOUR));
        VerrouillageMcpTools tools = new VerrouillageMcpTools(referentiel);

        SuppressionResult resultat = tools.unlock("V1", null);

        assertThat(resultat.supprime()).isTrue();
        assertThat(referentiel.supprimes).containsExactly("V1");
    }
}
