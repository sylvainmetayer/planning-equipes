package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.publication.PlanPublicationService;
import dev.sylvain.planning.service.solve.PlanningPersistenceService;
import dev.sylvain.planning.service.referentiel.ReferenceDataService;
import io.quarkus.mailer.Mail;
import io.quarkus.mailer.MockMailbox;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;

/**
 * Resending one animateur their planning ({@code /api/planning/envoi}),
 * against the real database and the mock mailbox: the PDF really is built from
 * the persisted planning and the espace link really carries the animateur's
 * token. Sending to everybody is no longer here — see
 * {@link PublicationResourceTest}.
 */
@QuarkusTest
class EnvoiPlanningResourceTest {

    private static final LocalDate JOUR = LocalDate.of(2026, 7, 11);
    private static final long CRENEAU_ID = 9201L;
    private static final String EMAIL_ALICE = "alice-envoi@example.org";

    @Inject
    PlanningPersistenceService persistence;

    @Inject
    PlanPublicationService publication;

    @Inject
    ReferenceDataService referenceData;

    @Inject
    MockMailbox mailbox;

    @BeforeEach
    void seed() {
        mailbox.clear();
        // Alice (with email) and Bruno (without) hold a seat; Chloé has an
        // email but no seat, so a global send must leave her alone.
        Animateur alice = new Animateur("MAIL-A", "Alice", "Martin", LocalDate.of(1990, 1, 1), false);
        Animateur bruno = new Animateur("MAIL-B", "Bruno", "Petit", LocalDate.of(1992, 2, 2), false);
        Animateur chloe = new Animateur("MAIL-C", "Chloé", "Durand", LocalDate.of(1995, 3, 3), false);
        Stand standUn = new Stand("MAIL-S1", "Stand mail un", Set.of(), 1, 1, false);
        Stand standDeux = new Stand("MAIL-S2", "Stand mail deux", Set.of(), 1, 1, false);
        Creneau creneau = new Creneau(CRENEAU_ID, 1, JOUR, LocalTime.of(14, 0), LocalTime.of(16, 0));
        PosteAffectation posteUn = new PosteAffectation("MAIL-P1", standUn, creneau);
        posteUn.setAnimateur(alice);
        PosteAffectation posteDeux = new PosteAffectation("MAIL-P2", standDeux, creneau);
        posteDeux.setAnimateur(bruno);
        persistence.persist(new PlanningEvenement(JOUR, List.of(alice, bruno, chloe), List.of(posteUn, posteDeux)));

        // persist() deliberately never writes emails; the fiche update does.
        donnerEmail("MAIL-A", EMAIL_ALICE);
        donnerEmail("MAIL-B", null);
        donnerEmail("MAIL-C", "chloe-envoi@example.org");
        // The individual resend sends the published plan, not the working one.
        PlansPublies.publier(publication);
        mailbox.clear();
    }

    @Test
    void lEnvoiIndividuelJointLePdfEtLeLienEspace() {
        given().contentType(ContentType.JSON)
                .when().post("/api/planning/envoi/animateur/MAIL-A")
                .then()
                .statusCode(200)
                .body("envoyes", equalTo(1));

        List<Mail> mails = mailbox.getMailsSentTo(EMAIL_ALICE);
        assertThat(mails).hasSize(1);
        Mail mail = mails.get(0);
        assertThat(mail.getSubject()).contains("votre planning individuel");
        assertThat(mail.getText())
                .contains("Bonjour Alice")
                .contains("/animateur/" + tokenOf("MAIL-A"));
        assertThat(mail.getAttachments()).hasSize(1);
        assertThat(mail.getAttachments().get(0).getName()).isEqualTo("planning-Alice-Martin.pdf");
        assertThat(mail.getAttachments().get(0).getContentType()).isEqualTo("application/pdf");
    }

    @Test
    void unAnimateurSansEmailRepondUneErreurExplicite() {
        given().contentType(ContentType.JSON)
                .when().post("/api/planning/envoi/animateur/MAIL-B")
                .then()
                .statusCode(400)
                .body("message", containsString("n'a pas d'adresse e-mail"));
        assertThat(mailbox.getTotalMessagesSent()).isZero();
    }

    @Test
    void unAnimateurInconnuRepondIntrouvable() {
        given().contentType(ContentType.JSON)
                .when().post("/api/planning/envoi/animateur/MAIL-FANTOME")
                .then()
                .statusCode(404);
    }

    private void donnerEmail(String animateurId, String email) {
        Animateur animateur = referenceData.listAnimateurs().stream()
                .filter(candidat -> candidat.getId().equals(animateurId))
                .findFirst()
                .orElseThrow();
        animateur.setEmail(email);
        referenceData.updateAnimateur(animateurId, animateur);
    }

    private String tokenOf(String animateurId) {
        return referenceData.listAnimateurs().stream()
                .filter(candidat -> candidat.getId().equals(animateurId))
                .findFirst()
                .orElseThrow()
                .getAccessToken();
    }
}
