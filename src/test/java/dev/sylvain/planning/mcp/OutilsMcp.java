package dev.sylvain.planning.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkiverse.mcp.server.Tool;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URISyntaxException;
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

    static List<Method> all() throws IOException, URISyntaxException {
        File dossier = new File(OutilsMcp.class
                        .getProtectionDomain()
                        .getCodeSource()
                        .getLocation()
                        .toURI())
                .getParentFile() // target/test-classes -> target
                .toPath()
                .resolve("classes")
                .resolve(OutilsMcp.class.getPackageName().replace('.', '/'))
                .toFile();
        assertThat(dossier).as("classes compilées du package mcp").isDirectory();

        List<Method> outils = new ArrayList<>();
        for (File file : dossier.listFiles((dir, name) -> name.endsWith(".class"))) {
            String nomClasse = OutilsMcp.class.getPackageName() + "."
                    + file.getName().substring(0, file.getName().length() - ".class".length());
            Class<?> classe;
            try {
                classe = Class.forName(nomClasse);
            } catch (ClassNotFoundException | NoClassDefFoundError e) {
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
