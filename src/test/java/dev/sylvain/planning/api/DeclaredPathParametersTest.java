package dev.sylvain.planning.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import org.eclipse.microprofile.openapi.OASFactory;
import org.eclipse.microprofile.openapi.models.OpenAPI;
import org.eclipse.microprofile.openapi.models.Operation;
import org.eclipse.microprofile.openapi.models.media.Schema;
import org.eclipse.microprofile.openapi.models.parameters.Parameter;
import org.junit.jupiter.api.Test;

/**
 * A fuzzer reading the published contract refused every espace route
 * (« Path parameter 'jeton' is not defined »): the segment is read by a
 * filter, so nothing declared it.
 */
class DeclaredPathParametersTest {

    @Test
    void undeclaredTemplateSegmentIsDeclaredAndADeclaredOneIsLeftAlone() {
        Operation espace = OASFactory.createOperation();
        Operation accord = OASFactory.createOperation()
                .addParameter(OASFactory.createParameter().name("demandeId").in(Parameter.In.PATH));
        OpenAPI openAPI = OASFactory.createOpenAPI()
                .paths(OASFactory.createPaths()
                        .addPathItem(
                                "/api/espace-animateur/{jeton}",
                                OASFactory.createPathItem().GET(espace))
                        .addPathItem(
                                "/api/espace-animateur/{jeton}/demandes-recues/{demandeId}/accord",
                                OASFactory.createPathItem().POST(accord)));

        new DeclaredPathParameters().filterOpenAPI(openAPI);

        assertThat(espace.getParameters()).singleElement().satisfies(parameter -> {
            assertThat(parameter.getName()).isEqualTo("jeton");
            assertThat(parameter.getIn()).isEqualTo(Parameter.In.PATH);
            assertThat(parameter.getRequired()).isTrue();
        });
        assertThat(accord.getParameters()).extracting(Parameter::getName).containsExactly("demandeId", "jeton");
    }

    @Test
    void pathItemDeclarationCountsAndASameNamedQueryParameterDoesNot() {
        Parameter itemJeton = OASFactory.createParameter().name("jeton").in(Parameter.In.PATH);
        Operation viaItem = OASFactory.createOperation();
        Operation viaQuery = OASFactory.createOperation()
                .addParameter(OASFactory.createParameter().name("token").in(Parameter.In.QUERY));
        OpenAPI openAPI = OASFactory.createOpenAPI()
                .paths(OASFactory.createPaths()
                        .addPathItem(
                                "/a/{jeton}",
                                OASFactory.createPathItem()
                                        .addParameter(itemJeton)
                                        .GET(viaItem))
                        .addPathItem("/b/{token}", OASFactory.createPathItem().GET(viaQuery)));

        new DeclaredPathParameters().filterOpenAPI(openAPI);

        assertThat(viaItem.getParameters()).isNullOrEmpty();
        assertThat(viaQuery.getParameters())
                .extracting(Parameter::getName, Parameter::getIn)
                .containsExactly(tuple("token", Parameter.In.QUERY), tuple("token", Parameter.In.PATH));
    }

    @Test
    void localDateTimeIsDescribedWithoutAnOffset() {
        Schema localDateTime = OASFactory.createSchema().format("date-time");
        OpenAPI openAPI = OASFactory.createOpenAPI()
                .components(OASFactory.createComponents().addSchema("LocalDateTime", localDateTime));

        new DeclaredPathParameters().filterOpenAPI(openAPI);

        assertThat(localDateTime.getFormat()).isEqualTo("local-date-time");
    }
}
