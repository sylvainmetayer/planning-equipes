package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The organisation's contact is stored per edition, trimmed, a blank half
 * stored as nothing, and refused when it could not be dialled or written to.
 */
@QuarkusTest
class ContactOrganisationResourceTest {

    /** The row outlives a test: the next class finds an edition without a contact again. */
    @AfterEach
    void clearContact() {
        save(contact(null, null)).statusCode(200);
    }

    @Test
    void anEditionThatNeverSetAContactAnswersTwoNulls() {
        given().when()
                .get("/api/parametres-contact")
                .then()
                .statusCode(200)
                .body("telephone", nullValue())
                .body("email", nullValue());
    }

    @Test
    void aContactIsStoredTrimmedAndReadBack() {
        save(contact(" 06 12 34 56 78 ", "orga@example.org  "))
                .statusCode(200)
                .body("telephone", equalTo("06 12 34 56 78"));

        given().when()
                .get("/api/parametres-contact")
                .then()
                .statusCode(200)
                .body("telephone", equalTo("06 12 34 56 78"))
                .body("email", equalTo("orga@example.org"));
    }

    @Test
    void aBlankHalfIsStoredAsNothing() {
        save(contact("+33 6 12 34 56 78", "   ")).statusCode(200).body("email", nullValue());
    }

    @Test
    void aNumberWithLettersOrAnAddressWithoutAtIsRefused() {
        save(contact("appelez Paul", null)).statusCode(400).body("message", containsString("téléphone"));
        save(contact(null, "orga.example.org")).statusCode(400).body("message", containsString("@"));
    }

    private static Map<String, Object> contact(String telephone, String email) {
        Map<String, Object> body = new HashMap<>();
        body.put("telephone", telephone);
        body.put("email", email);
        return body;
    }

    private static ValidatableResponse save(Map<String, Object> body) {
        return given().contentType(ContentType.JSON)
                .body(body)
                .when()
                .put("/api/parametres-contact")
                .then();
    }
}
