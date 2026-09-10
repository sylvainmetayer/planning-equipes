package dev.sylvain.planning.service.scenario;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/** The scenarios shipped under {@code src/main/resources/scenarios}. */
final class ScenariosLivres {

    private ScenariosLivres() {}

    static List<Path> all() throws IOException {
        try (Stream<Path> files = Files.list(Path.of("src/main/resources/scenarios"))) {
            return files.filter(path -> path.toString().matches(".*\\.ya?ml"))
                    .sorted()
                    .toList();
        }
    }

    static String nom(Path scenario) {
        return scenario.getFileName().toString().replaceAll("\\.ya?ml$", "");
    }
}
