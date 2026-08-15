package dev.sylvain.planning.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.mcp.AnimateurMcpTools.AnimateurView;
import dev.sylvain.planning.mcp.CreneauMcpTools.CreneauView;
import dev.sylvain.planning.mcp.StandMcpTools.StandView;
import dev.sylvain.planning.service.ReferenceDataService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

/**
 * End-to-end coverage of the referential mutations opened up by the follow-up
 * comment of issue #107 ("tous les endpoints peuvent être implémentés via
 * MCP"), against the real database.
 *
 * <p>The privacy-critical assertion here is
 * {@link #modifierUnAnimateurNeDetruitPasSesDonneesPersonnelles}: MCP edits an
 * animateur without ever seeing nom/prénom/date de naissance, so the merge
 * semantics of {@code modifier_animateur} are the only thing standing between
 * an innocuous "rends A-MCP-1 manager" and a silent wipe of the fields that
 * drive the legal constraints.
 */
@QuarkusTest
class ReferentielMcpToolsTest {

    @Inject
    AnimateurMcpTools animateurTools;

    @Inject
    StandMcpTools standTools;

    @Inject
    CreneauMcpTools creneauTools;

    @Inject
    ReferenceDataService referenceDataService;

    @Test
    void modifierUnAnimateurNeDetruitPasSesDonneesPersonnelles() {
        Animateur existant = new Animateur("A-MCP-1", "Ada", "Lovelace", LocalDate.of(2010, 6, 1), false);
        referenceDataService.createAnimateur(existant);
        standTools.creer_typologie("TYPO-MCP-1", "Jeux de stratégie");

        AnimateurView modifie = animateurTools.modifier_animateur("A-MCP-1", true,
                Map.of("TYPO-MCP-1", "REFERENT"), List.of("TYPO-MCP-1"), List.of("2026-07-18"));

        assertThat(modifie.manager()).isTrue();
        assertThat(modifie.souhaits()).containsExactly("TYPO-MCP-1");
        assertThat(modifie.joursIndisponibles()).containsExactly(LocalDate.of(2026, 7, 18));

        Animateur relu = referenceDataService.listAnimateurs().stream()
                .filter(animateur -> animateur.getId().equals("A-MCP-1"))
                .findFirst()
                .orElseThrow();
        assertThat(relu.getPrenom()).isEqualTo("Ada");
        assertThat(relu.getNom()).isEqualTo("Lovelace");
        assertThat(relu.getDateNaissance()).isEqualTo(LocalDate.of(2010, 6, 1));

        animateurTools.supprimer_animateur("A-MCP-1");
        standTools.supprimer_typologie("TYPO-MCP-1");
    }

    @Test
    void creerPuisModifierPuisSupprimerUnStand() {
        standTools.creer_typologie("TYPO-MCP-2", "Jeux d'adresse");
        StandView cree = standTools.creer_stand("STAND-MCP-1", "Tir à l'arc", List.of("TYPO-MCP-2"), 2, 4,
                true, false, "EPUISANT", null);

        assertThat(cree.effectifMin()).isEqualTo(2);
        assertThat(cree.niveauEffort().name()).isEqualTo("EPUISANT");

        StandView modifie = standTools.modifier_stand("STAND-MCP-1", "Tir à l'arc (grand)", null, null, 6,
                null, null, null, null);

        assertThat(modifie.nom()).isEqualTo("Tir à l'arc (grand)");
        assertThat(modifie.effectifMax()).isEqualTo(6);
        // Untouched arguments keep their persisted value rather than resetting.
        assertThat(modifie.effectifMin()).isEqualTo(2);
        assertThat(modifie.reserveMajeurs()).isTrue();
        assertThat(modifie.typologiesProposees()).containsExactly("TYPO-MCP-2");

        StandView avecFermeture = standTools.ajouter_fermeture_stand("STAND-MCP-1", "2026-07-18", "12:00", "14:00",
                "Pause repas");
        assertThat(avecFermeture.fermetures()).hasSize(1);

        assertThat(standTools.effacer_plages_stand("STAND-MCP-1", "2026-07-18").fermetures()).isEmpty();

        assertThat(standTools.supprimer_stand("STAND-MCP-1").supprime()).isTrue();
        standTools.supprimer_typologie("TYPO-MCP-2");
    }

    @Test
    void creerUnGroupeEtSesCreneaux() {
        creneauTools.creer_groupe_creneaux("GRP-MCP-1", "Amplitudes MCP");
        CreneauView creneau = creneauTools.creer_creneau(1, "2026-07-18", "09:00", "13:00", "GRP-MCP-1");

        assertThat(creneau.groupeId()).isEqualTo("GRP-MCP-1");
        assertThat(creneauTools.lister_groupes_creneaux())
                .anySatisfy(groupe -> assertThat(groupe.id()).isEqualTo("GRP-MCP-1"));

        CreneauView modifie = creneauTools.modifier_creneau(creneau.id(), null, null, "10:00", null, null);
        assertThat(modifie.heureDebut()).hasToString("10:00");
        assertThat(modifie.heureFin()).hasToString("13:00");

        creneauTools.supprimer_creneau(creneau.id());
        creneauTools.supprimer_groupe_creneaux("GRP-MCP-1");
    }

    @Test
    void uneDateMalFormeeRemonteUnMessageExploitable() {
        assertThatThrownBy(() -> creneauTools.creer_creneau(1, "18/07/2026", "09:00", "13:00", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("AAAA-MM-JJ");
    }

    @Test
    void unNiveauDEffortInconnuListeLesValeursPossibles() {
        assertThatThrownBy(() -> standTools.creer_stand("STAND-MCP-2", "Stand", null, 1, 1, null, null,
                "TRANQUILLE", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("NORMAL");
    }
}
