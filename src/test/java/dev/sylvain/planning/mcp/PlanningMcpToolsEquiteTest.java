package dev.sylvain.planning.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.ParametresLegaux;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.mcp.PlanningMcpTools.EquiteView;
import dev.sylvain.planning.mcp.PlanningMcpTools.LigneEquiteView;
import dev.sylvain.planning.service.analyse.EquiteService;
import java.lang.reflect.RecordComponent;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * {@code equite_planning} hands the assistant the same table the screen
 * shows, per animateur id — the name the REST line carries never crosses.
 */
class PlanningMcpToolsEquiteTest {

    private static final LocalDate SAMEDI = LocalDate.of(2026, 8, 15);

    @Test
    void theViewCarriesEveryColumnAndNoName() {
        Animateur ada = new Animateur("A1", "Ada", "Lovelace", LocalDate.of(1990, 1, 1), false);
        Stand stand = new Stand("S1", "Échecs", Set.of(), 1, 2, false);
        PosteAffectation poste =
                new PosteAffectation("P1", stand, new Creneau(1L, 1, SAMEDI, LocalTime.of(18, 0), LocalTime.of(22, 0)));
        poste.setAnimateur(ada);
        PlanningEvenement planning = new PlanningEvenement(SAMEDI, List.of(ada), List.of(poste));

        EquiteView vue = PlanningMcpTools.toView(
                EquiteService.compute(planning, new ParametresLegaux(), Set.of("equilibrerCharge")));

        assertThat(vue.heureDebutSoiree()).isEqualTo(LocalTime.of(20, 0));
        assertThat(vue.semaines()).containsExactly("2026-W33");
        assertThat(vue.lignes()).singleElement().satisfies(ligne -> {
            assertThat(ligne.animateurId()).isEqualTo("A1");
            assertThat(ligne.heuresTotal()).isEqualTo(4.0);
            assertThat(ligne.heuresSoiree()).isEqualTo(2.0);
            assertThat(ligne.heuresWeekEnd()).isEqualTo(4.0);
        });
        assertThat(vue.syntheses()).containsKey("heuresSoiree");
        assertThat(vue.colonnesSolveur())
                .filteredOn(colonne -> colonne.contrainte().equals("equilibrerCharge"))
                .singleElement()
                .satisfies(colonne -> assertThat(colonne.active()).isFalse());
        assertThat(Arrays.stream(LigneEquiteView.class.getRecordComponents()).map(RecordComponent::getName))
                .doesNotContain("nom")
                .contains("tauxSouhaits", "plusLongueSerie");
    }

    @Test
    void anEmptyPlanGivesAnEmptyView() {
        EquiteView vue = PlanningMcpTools.toView(EquiteService.compute(null, null, null));

        assertThat(vue.lignes()).isEmpty();
        assertThat(vue.syntheses()).isEmpty();
    }
}
