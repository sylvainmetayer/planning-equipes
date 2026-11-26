package dev.sylvain.planning.api;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.Path;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * An organiser account works in its own editions and never settles what belongs
 * to the whole instance. Two nets: every resource is classified (a new one
 * fails here until somebody decides), and the routes kept for the
 * administrators are exactly the argued inventory below.
 */
class InstanceRoutesStructuralTest {

    /** Open to an organiser, in the edition its request designates (EditionContext narrows it). */
    static final Set<String> PAR_EDITION = Set.of(
            "AlerteResource",
            "AffichageMuralResource",
            "AnimateurResource",
            "ArchiveEvenementResource",
            "ChangementsJourneeResource",
            "AffectationExplanationResource",
            "CreneauResource",
            "CreneauAvailabilityResource",
            "EnvoiPlanningResource",
            "EquiteResource",
            "EmplacementResource",
            "ConstraintResource",
            "ConsigneResource",
            "JourJResource",
            "ContrainteAdHocResource",
            "DemandeEchangeResource",
            "HistoriqueResource",
            "HorlogeResource",
            "DeclarationDisponibiliteResource",
            "EditionResource",
            "FeasibilityResource",
            "FragiliteResource",
            "JoursFeriesResource",
            "MargeResource",
            "PlanningResource",
            "VersionPlanResource",
            "PlanSnapshotResource",
            "PlanningExportResource",
            "StaffingResource",
            "ParametresResource",
            "FormationResource",
            "SolveInputsResource",
            "PlanningHoursResource",
            "StandResource",
            "JourneeTypeResource",
            "KpiResource",
            "VerrouillageResource",
            "PauseResource",
            "RealiseResource",
            "OuvertureStandsResource",
            "PublicationResource",
            "ReferenceDataResource",
            "TypologieResource",
            "SolverJobResource",
            "TypologieAnalyseResource",
            "ValidationJourneeResource",
            "WeatherResource",
            "ReglageResponsablesResource");

    /** Open to anybody on purpose (a token is the credential, or the route is public). */
    private static final Set<String> PUBLIQUES = Set.of(
            "AuthResource",
            "ConfigResource",
            "BrandingResource",
            "MentionsLegalesResource",
            "EspaceAnimateurResource",
            "AbonnementIcsResource",
            "MuralResource");

    /** Opened by a right of its own, and by nothing else: the responsable de stand's reads. */
    static final Set<String> RESPONSABLE = Set.of("ResponsableResource");

    /** Opened by the RH right (or an administrator), edition named in the path: the compliance report. */
    private static final Set<String> RH = Set.of("RhResource");

    /** Reserved to the administrators as a whole resource. */
    private static final Set<String> INSTANCE = Set.of(
            "CompteResource",
            "WebhookResource",
            "BackupResource",
            "DebugResource",
            "DatabaseResource",
            "ComputeMachineResource",
            "McpResource");

    /** And route by route inside an edition-level resource. */
    private static final Set<String> INSTANCE_ROUTES = Set.of(
            "EditionResource#create",
            "EditionResource#rename",
            "EditionResource#duplicate",
            "EditionResource#delta",
            "EditionResource#exportDeltaCsv",
            "EditionResource#activationPreview",
            "EditionResource#activate",
            "EditionResource#deactivate",
            "EditionResource#situations",
            "EditionResource#delete",
            "HistoriqueResource#connexions",
            "HorlogeResource#setDateJourJ",
            "ReglageResponsablesResource#put");

    static Set<Class<?>> resources() throws IOException, ClassNotFoundException {
        Set<Class<?>> classes = new java.util.HashSet<>();
        try (Stream<java.nio.file.Path> files =
                Files.list(java.nio.file.Path.of("src/main/java/dev/sylvain/planning/api"))) {
            for (java.nio.file.Path file :
                    files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String name = file.getFileName().toString().replace(".java", "");
                Class<?> type = Class.forName("dev.sylvain.planning.api." + name);
                if (type.isAnnotationPresent(Path.class)) {
                    classes.add(type);
                }
            }
        }
        return classes;
    }

    @Test
    void everyResourceIsClassified() throws Exception {
        Set<String> classified = Stream.of(PAR_EDITION, PUBLIQUES, INSTANCE, RESPONSABLE, RH)
                .flatMap(Set::stream)
                .collect(Collectors.toSet());
        Set<String> unclassified = resources().stream()
                .map(Class::getSimpleName)
                .filter(n -> !classified.contains(n))
                .collect(Collectors.toCollection(TreeSet::new));
        assertThat(unclassified)
                .as("a resource in no list: instance-wide (add @InstanceOnly) or per edition?")
                .isEmpty();
    }

    @Test
    void theRoutesKeptForAdministratorsAreTheArguedInventory() throws Exception {
        Set<String> wholeClasses = new TreeSet<>();
        Set<String> routes = new TreeSet<>();
        for (Class<?> type : resources()) {
            if (type.isAnnotationPresent(InstanceOnly.class) || type.isAnnotationPresent(RolesAllowed.class)) {
                wholeClasses.add(type.getSimpleName());
            }
            for (Method m : type.getDeclaredMethods()) {
                if (m.isAnnotationPresent(InstanceOnly.class)) {
                    routes.add(type.getSimpleName() + "#" + m.getName());
                }
            }
        }
        assertThat(wholeClasses).isEqualTo(new TreeSet<>(INSTANCE));
        assertThat(routes).isEqualTo(new TreeSet<>(INSTANCE_ROUTES));
    }
}
