package dev.sylvain.planning.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkiverse.mcp.server.Tool;
import java.io.File;
import java.lang.reflect.Method;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * Every {@code @Tool} method of the {@code mcp} package, found by walking the
 * compiled classes rather than by listing them.
 *
 * <p>Shared by the structural guards that must hold for <em>all</em> tools —
 * privacy ({@link McpConfidentialiteStructurelleTest}) and edition scoping
 * ({@link McpEditionStructurelleTest}). Enumerating the tools by hand in each
 * of them would defeat the point: a new tool would simply not be covered.</p>
 */
final class OutilsMcp {

    private OutilsMcp() {}

    static List<Method> all() throws URISyntaxException {
        // Anchored on a production class of the package, read through the
        // launcher's class loader: under a @QuarkusTest with quarkus-jacoco
        // (-Pcoverage), this class is defined from instrumented bytes and
        // carries no code source to start from.
        URL anchor = ClassLoader.getSystemClassLoader()
                .getResource(EditionArg.class.getName().replace('.', '/') + ".class");
        assertThat(anchor).as("EditionArg.class sur le classpath").isNotNull();
        File dossier = new File(anchor.toURI()).getParentFile();
        assertThat(dossier).as("classes compilées du package mcp").isDirectory();

        List<Method> outils = new ArrayList<>();
        for (File file : dossier.listFiles((dir, name) -> name.endsWith(".class"))) {
            String nomClasse = OutilsMcp.class.getPackageName() + "."
                    + file.getName().substring(0, file.getName().length() - ".class".length());
            Class<?> classe;
            try {
                classe = Class.forName(nomClasse);
            } catch (ClassNotFoundException | NoClassDefFoundError _) {
                continue; // build-time generated companion class, not a tool holder
            }
            for (Method methode : classe.getDeclaredMethods()) {
                if (methode.isAnnotationPresent(Tool.class)) {
                    outils.add(methode);
                }
            }
        }
        assertThat(outils).as("les outils MCP doivent être découverts").isNotEmpty();
        return outils;
    }
}
