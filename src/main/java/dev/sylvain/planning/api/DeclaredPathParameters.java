package dev.sylvain.planning.api;

import io.quarkus.smallrye.openapi.OpenApiFilter;
import java.util.List;
import java.util.regex.Pattern;
import org.eclipse.microprofile.openapi.OASFactory;
import org.eclipse.microprofile.openapi.OASFilter;
import org.eclipse.microprofile.openapi.models.OpenAPI;
import org.eclipse.microprofile.openapi.models.Operation;
import org.eclipse.microprofile.openapi.models.PathItem;
import org.eclipse.microprofile.openapi.models.media.Schema;
import org.eclipse.microprofile.openapi.models.parameters.Parameter;

/**
 * Two corrections the generated contract needs before a client can trust it.
 *
 * <p>A path segment read by a filter rather than a {@code @PathParam} — the
 * espace's {@code {jeton}}, the subscription's {@code {token}} — went
 * undeclared, which makes those operations invalid OpenAPI: a generated client
 * or a fuzzer cannot address them at all. Every {@code {name}} of a path
 * template is declared here when its operation does not declare it.</p>
 *
 * <p>A {@code LocalDateTime} is written without an offset, which the
 * {@code date-time} format SmallRye gives it forbids; it is described as
 * {@code local-date-time}, the way {@code LocalTime} already is.</p>
 */
@OpenApiFilter(OpenApiFilter.RunStage.BUILD)
public class DeclaredPathParameters implements OASFilter {

    private static final Pattern TEMPLATE = Pattern.compile("\\{([^}/]+)}");

    @Override
    public void filterOpenAPI(OpenAPI openAPI) {
        if (openAPI.getPaths() != null && openAPI.getPaths().getPathItems() != null) {
            openAPI.getPaths().getPathItems().forEach(DeclaredPathParameters::declare);
        }
        if (openAPI.getComponents() != null && openAPI.getComponents().getSchemas() != null) {
            Schema localDateTime = openAPI.getComponents().getSchemas().get("LocalDateTime");
            if (localDateTime != null) {
                localDateTime.setFormat("local-date-time");
            }
        }
    }

    private static void declare(String path, PathItem item) {
        List<String> names =
                TEMPLATE.matcher(path).results().map(match -> match.group(1)).toList();
        if (names.isEmpty() || item.getOperations() == null) {
            return;
        }
        for (Operation operation : item.getOperations().values()) {
            for (String name : names) {
                if (!declares(item.getParameters(), name) && !declares(operation.getParameters(), name)) {
                    operation.addParameter(OASFactory.createParameter()
                            .name(name)
                            .in(Parameter.In.PATH)
                            .required(true)
                            .schema(OASFactory.createSchema().addType(Schema.SchemaType.STRING)));
                }
            }
        }
    }

    private static boolean declares(List<Parameter> parameters, String name) {
        return parameters != null
                && parameters.stream()
                        .anyMatch(parameter ->
                                parameter.getIn() == Parameter.In.PATH && name.equals(parameter.getName()));
    }
}
