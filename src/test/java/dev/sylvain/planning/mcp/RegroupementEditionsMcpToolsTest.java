package dev.sylvain.planning.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.mcp.EditionMcpTools.EditionView;
import dev.sylvain.planning.mcp.ParametresMcpTools.ParametresLegauxView;
import dev.sylvain.planning.mcp.PublicationMcpTools.PublicationEnvoiView;
import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.EditionContext;
import dev.sylvain.planning.service.edition.EditionService;
import dev.sylvain.planning.service.solve.PlanningPersistenceService;
import io.quarkiverse.mcp.server.ToolCallException;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import javax.sql.DataSource;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The tools 4.0 groups for the parameters, the editions, the scenarios, the
 * publication and the backups (ADR 0127), each called once as it should be and
 * once as it must be refused.
 */
@QuarkusTest
class RegroupementEditionsMcpToolsTest {

    private static final String NOM_TEST = "Regroupement MCP";
    private static final String NOM_COPIE = "Regroupement MCP copie";
    private static final LocalDate JOUR = LocalDate.of(2026, 8, 14);

    @Inject
    EditionMcpTools editionTools;

    @Inject
    ScenarioMcpTools scenarioTools;

    @Inject
    PublicationMcpTools publicationTools;

    @Inject
    SauvegardeMcpTools sauvegardeTools;

    @Inject
    ParametresMcpTools parametresTools;

    @Inject
    EditionService editionService;

    @Inject
    EditionContext editionContext;

    @Inject
    PlanningPersistenceService persistence;

    @Inject
    DataSource dataSource;

    @AfterEach
    void clean() throws SQLException {
        editionService.listEditions().stream()
                .filter(edition -> NOM_TEST.equals(edition.getNom()) || NOM_COPIE.equals(edition.getNom()))
                .forEach(edition -> editionService.delete(edition.getId()));
        forgetPublications();
        persistence.clearDatabase();
    }

    @Test
    void anEditionIsCreatedCopiedRenamedAndRead() {
        EditionView vide = editionTools.createEdition(NOM_TEST, null, null);
        assertThat(vide.active()).isFalse();
        assertThat(vide.nombreCreneaux()).isZero();

        EditionView copie = editionTools.createEdition(NOM_COPIE, "E1", false);
        assertThat(copie.id()).isNotEqualTo(vide.id());
        assertThat(copie.nombreAnimateurs()).isZero();

        String renomme = NOM_TEST + " bis";
        assertThat(editionTools.modifyEdition(renomme, null, vide.id()).nom()).isEqualTo(renomme);
        editionTools.modifyEdition(NOM_TEST, null, vide.id());
        // Deactivating an inactive edition changes nothing, and says so.
        assertThat(editionTools.modifyEdition(null, false, copie.id()).active()).isFalse();
        assertThat(editionTools.consultEdition("E1")).isNotNull();

        refused(
                () -> editionTools.createEdition(NOM_TEST, null, false),
                BusinessError.Invalid.class,
                "avec_animateurs");
        refused(() -> editionTools.modifyEdition(null, null, vide.id()), BusinessError.Invalid.class, "« nom »");
        refused(
                () -> editionTools.modifyEdition(NOM_TEST, true, vide.id()),
                BusinessError.Invalid.class,
                "pas les deux");
        assertThatThrownBy(() -> editionTools.consultEdition("edition-qui-nexiste-pas"))
                .isInstanceOf(ToolCallException.class)
                .hasMessageContaining("Édition inconnue");
    }

    @Test
    void aScenarioIsCheckedImportedThenEmptied() {
        assertThat(scenarioTools.diagnoseScenario("stands: [").valide()).isFalse();
        assertThat(scenarioTools.diagnoseScenario("").erreurs()).isNotEmpty();

        assertThat(scenarioTools.importScenario("scenario.yml", null, "E1").importe())
                .isTrue();
        assertThat(scenarioTools.deleteEditionData("E1").reinitialise()).isTrue();

        refused(() -> scenarioTools.importScenario(null, null, "E1"), BusinessError.Invalid.class, "« yaml »");
        refused(
                () -> scenarioTools.importScenario("scenario.yml", "stands: []", "E1"),
                BusinessError.Invalid.class,
                "pas les deux");
        assertThatThrownBy(() -> scenarioTools.deleteEditionData("edition-qui-nexiste-pas"))
                .isInstanceOf(ToolCallException.class)
                .hasMessageContaining("Édition inconnue");
    }

    @Test
    void aPlanIsPublishedReadAndRemindedUnderOneTool() throws SQLException {
        forgetPublications();
        persistence.clearDatabase();
        Animateur alice = new Animateur("RGP-A", "Alice", "Martin", LocalDate.of(1990, 1, 1), false);
        Stand stand = new Stand("RGP-S1", "Stand regroupé", Set.of(), 1, 1, false);
        PosteAffectation poste = new PosteAffectation(
                "RGP-P1", stand, new Creneau(970_330_000L, 1, JOUR, LocalTime.of(10, 0), LocalTime.of(12, 0)));
        poste.setAnimateur(alice);
        persistence.persist(new PlanningEvenement(JOUR, List.of(alice), List.of(poste)));

        assertThat(publicationTools.consultPublication(null, null, "E1").etat().jamaisPublie())
                .isTrue();
        PublicationEnvoiView publication = publicationTools.publishPlanning(null, null, "E1");
        assertThat(publication.mode()).isEqualTo("publier");
        assertThat(publication.snapshotId()).isNotNull();
        assertThat(publication.relance()).isNull();

        assertThat(publicationTools
                        .consultPublication("destinataires", null, "E1")
                        .destinataires())
                .extracting(PublicationMcpTools.DestinataireView::animateurId)
                .containsExactly("RGP-A");
        assertThat(publicationTools
                        .consultPublication("confirmations", null, "E1")
                        .confirmations()
                        .jamaisPublie())
                .isFalse();
        PublicationEnvoiView relance = publicationTools.publishPlanning("relancer", List.of("RGP-A"), "E1");
        assertThat(relance.relance().sansEmail()).containsExactly("RGP-A");
        assertThat(relance.envoyes()).isNull();

        refused(() -> publicationTools.consultPublication("etat", 1L, "E1"), BusinessError.Invalid.class, "snapshotId");
        refused(
                () -> publicationTools.publishPlanning(null, List.of("RGP-A"), "E1"),
                BusinessError.Invalid.class,
                "animateurIds");
        refused(
                () -> publicationTools.publishPlanning("renvoyer", List.of("RGP-A", "RGP-B"), "E1"),
                BusinessError.Invalid.class,
                "un seul id");
        refused(
                () -> publicationTools.publishPlanning("relancer", null, "E1"),
                BusinessError.Invalid.class,
                "au moins");
    }

    @Test
    void theBackupsAndTheFormerParameterNamesStillAnswer() {
        assertThat(sauvegardeTools.consultBackups()).isEqualTo(sauvegardeTools.backupState());

        ParametresLegauxView legal = parametresTools.getParametresLegauxAlias("E1");
        assertThat(parametresTools.getParametres("legal", "E1").parametres()).isEqualTo(legal);
        refused(
                () -> parametresTools.updateParametresNotificationsAlias("23:30", null, null, "E1"),
                BusinessError.class,
                "23:00");
    }

    private static void refused(ThrowingCallable call, Class<? extends BusinessError> cause, String phrase) {
        assertThatThrownBy(call)
                .isInstanceOf(ToolCallException.class)
                .hasCauseInstanceOf(cause)
                .hasMessageContaining(phrase);
    }

    private void forgetPublications() throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement("DELETE FROM version_plan WHERE edition_id = ?")) {
            ps.setString(1, "E1");
            ps.executeUpdate();
        }
    }
}
