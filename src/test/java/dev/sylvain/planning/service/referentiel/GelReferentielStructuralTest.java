package dev.sylvain.planning.service.referentiel;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.service.scenario.ScenarioImportService;
import dev.sylvain.planning.service.solve.PlanningPersistenceService;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * No write path of a family the organiser can freeze goes without its guard
 * (ADR 0052) — and the list of what is deliberately left open is written down,
 * never left to omission.
 *
 * <p>The guard sits on the services, because they are the one door every path
 * goes through: the REST resources, the bulk edit (one {@code PUT} per row),
 * the CSV and grid imports, the MCP tools all call them. So the test reads
 * every non-private, non-static method of the services that write a freezable
 * family, and asks each one to be in exactly one of five places:</p>
 *
 * <ul>
 *   <li>annotated {@link RefusedWhileFrozen} — the whole method writes the
 *       family, the interceptor refuses it before it starts;</li>
 *   <li>{@link #CHECKED_IN_BODY} — the method writes part of a family (a stand
 *       renamed is not a stand whose headcount moved) and calls
 *       {@code gel.refuseIfFrozen} itself once it compared; the call is read
 *       back in its source;</li>
 *   <li>{@link #DELEGATES} — the facade's methods, which write nothing
 *       themselves and hand over to a method classified here; the call is read
 *       back in their source;</li>
 *   <li>{@link #OPEN} — writes no freeze covers, each with its reason;</li>
 *   <li>a read, recognised by its name ({@link #READ}).</li>
 * </ul>
 *
 * <p>A new write method in none of them fails the build. And since an
 * interceptor is bypassed by a call on {@code this}, an annotated method is
 * never called from inside its own class.</p>
 */
class GelReferentielStructuralTest {

    private static final Path SOURCES = Path.of("src/main/java/dev/sylvain/planning");

    /** The services that write stands, timeslots, game categories, locations or competences. */
    private static final List<Class<?>> GUARDED = List.of(
            StandService.class,
            CreneauService.class,
            JourneeTypeService.class,
            TypologieService.class,
            EmplacementService.class,
            AnimateurService.class,
            CompetencesGrilleService.class,
            ReferentielCsvImportService.class,
            StandGrilleImportService.class,
            AnimateurCsvImportService.class,
            ReferenceDataService.class,
            ScenarioImportService.class);

    /** A read says so in its name; nothing named this way may write. */
    private static final Pattern READ = Pattern.compile("^(list|find|get|count|preview|resolve|exemple|export|volumes"
            + "|labels|validate|calendrier|etat|ninja|controler|gridAnomalies|diagnose|fenetresRepas|snapshot"
            + "|typologieNinja|abonnementToken|idBy|idsBy|typologyIdsBy).*");

    /** Method → the method of the same class whose body calls {@code gel.refuseIfFrozen}. */
    private static final Map<String, String> CHECKED_IN_BODY = Map.ofEntries(
            Map.entry("StandService#update(String,Stand)", "update"),
            Map.entry("StandService#compactHoraires(boolean)", "compactHoraires"),
            Map.entry("TypologieService#update(String,TypologieItem)", "update"),
            Map.entry("AnimateurService#update(String,Animateur)", "update"),
            Map.entry("AnimateurService#update(String,Animateur,Animateur)", "update"),
            Map.entry("ReferentielCsvImportService#apply(ImportTarget,ReferentielCsvImportRequest)", "apply"),
            Map.entry("AnimateurCsvImportService#apply(AnimateurCsvImportRequest)", "apply"),
            Map.entry("ScenarioImportService#importBundled(String)", "importScenario"),
            Map.entry("ScenarioImportService#importYaml(String)", "importScenario"));

    /** A facade method → the method, classified in this test, it hands the write over to. */
    private static final Map<String, String> DELEGATES = Map.ofEntries(
            Map.entry("ReferenceDataService#createAnimateur(Animateur)", "AnimateurService#create(Animateur)"),
            Map.entry(
                    "ReferenceDataService#updateAnimateur(String,Animateur)",
                    "AnimateurService#update(String,Animateur)"),
            Map.entry(
                    "ReferenceDataService#writeAnimateur(Animateur)",
                    "ReferenceDataService#createAnimateur(Animateur)"),
            Map.entry(
                    "ReferenceDataService#writeAnimateur(String,Animateur)",
                    "AnimateurService#update(String,Animateur,Animateur)"),
            Map.entry("ReferenceDataService#deleteAnimateur(String)", "AnimateurService#delete(String)"),
            Map.entry(
                    "ReferenceDataService#regenerateAnimateurToken(String)",
                    "AnimateurService#regenerateToken(String)"),
            Map.entry(
                    "ReferenceDataService#regenerateAbonnementToken(String)",
                    "AnimateurService#regenerateAbonnementToken(String)"),
            Map.entry("ReferenceDataService#createStand(Stand)", "StandService#create(Stand)"),
            Map.entry("ReferenceDataService#updateStand(String,Stand)", "StandService#update(String,Stand)"),
            Map.entry("ReferenceDataService#writeStand(Stand)", "ReferenceDataService#createStand(Stand)"),
            Map.entry(
                    "ReferenceDataService#writeStand(Stand,List,Emplacement)", "StandService#create(Connection,Stand)"),
            Map.entry(
                    "ReferenceDataService#writeStand(String,Stand)", "ReferenceDataService#updateStand(String,Stand)"),
            Map.entry("ReferenceDataService#deleteStand(String)", "StandService#delete(String)"),
            Map.entry("ReferenceDataService#compactHoraires(boolean)", "StandService#compactHoraires(boolean)"),
            Map.entry("ReferenceDataService#saisirGrilleHoraires(List)", "StandService#saisirGrille(List)"),
            Map.entry("ReferenceDataService#createEmplacement(Emplacement)", "EmplacementService#create(Emplacement)"),
            Map.entry(
                    "ReferenceDataService#updateEmplacement(String,Emplacement)",
                    "EmplacementService#update(String,Emplacement)"),
            Map.entry("ReferenceDataService#deleteEmplacement(String)", "EmplacementService#delete(String)"),
            Map.entry("ReferenceDataService#createCreneau(Creneau)", "CreneauService#create(Creneau)"),
            Map.entry("ReferenceDataService#updateCreneau(Long,Creneau)", "CreneauService#update(Long,Creneau)"),
            Map.entry("ReferenceDataService#writeCreneau(Creneau)", "ReferenceDataService#createCreneau(Creneau)"),
            Map.entry(
                    "ReferenceDataService#writeCreneau(Long,Creneau)",
                    "ReferenceDataService#updateCreneau(Long,Creneau)"),
            Map.entry(
                    "ReferenceDataService#writeCreneau(Connection,Creneau)",
                    "CreneauService#create(Connection,Creneau)"),
            Map.entry("ReferenceDataService#deleteCreneau(Long)", "CreneauService#delete(Long)"),
            Map.entry("ReferenceDataService#createCreneaux(List)", "CreneauService#createInBulk(List)"),
            Map.entry("ReferenceDataService#deleteCreneaux(Collection)", "CreneauService#deleteInBulk(Collection)"),
            Map.entry(
                    "ReferenceDataService#deleteCreneaux(Connection,Collection)",
                    "CreneauService#deleteInBulk(Connection,Collection)"),
            Map.entry(
                    "ReferenceDataService#createRecurrence(RegleRecurrence)",
                    "ReferenceDataService#createCreneaux(List)"),
            Map.entry("ReferenceDataService#applyDerivation(Parametres,boolean)", "CreneauService#replace(List)"),
            Map.entry(
                    "ReferenceDataService#importCsvReferentiel(ImportTarget,ReferentielCsvImportRequest)",
                    "ReferentielCsvImportService#apply(ImportTarget,ReferentielCsvImportRequest)"),
            Map.entry("ReferenceDataService#createJourneeType(JourneeType)", "JourneeTypeService#create(JourneeType)"),
            Map.entry(
                    "ReferenceDataService#updateJourneeType(long,JourneeType)",
                    "JourneeTypeService#update(long,JourneeType)"),
            Map.entry("ReferenceDataService#deleteJourneeType(long)", "JourneeTypeService#delete(long)"),
            Map.entry(
                    "ReferenceDataService#setCalendrierJourneesTypes(List)", "JourneeTypeService#setCalendrier(List)"),
            Map.entry("ReferenceDataService#applyJourneesTypes()", "JourneeTypeService#apply()"),
            Map.entry("ReferenceDataService#reconnaitreJourneesTypes()", "JourneeTypeService#reconnaitre()"),
            Map.entry("ReferenceDataService#importJourneesTypes(List,List)", "JourneeTypeService#importer(List,List)"),
            Map.entry(
                    "ReferenceDataService#importTypologie(TypologieItem)", "TypologieService#importer(TypologieItem)"),
            Map.entry("ReferenceDataService#createTypologie(TypologieItem)", "TypologieService#create(TypologieItem)"),
            Map.entry(
                    "ReferenceDataService#updateTypologie(String,TypologieItem)",
                    "TypologieService#update(String,TypologieItem)"),
            Map.entry("ReferenceDataService#deleteTypologie(String)", "TypologieService#delete(String)"));

    /** Writes no freeze covers, each with its reason. */
    private static final Map<String, String> OPEN = Map.ofEntries(
            Map.entry(
                    "CreneauService#create(Connection,Creneau)",
                    "the consigne's one grid write: consignes stay open under a freeze (ADR 0043), and"
                            + " consignesOnlyReachTheGridThroughTheirOwnDoor holds that nothing else calls it"),
            Map.entry("CreneauService#deleteInBulk(Connection,Collection)", "same, for the timeslots a consigne added"),
            Map.entry(
                    "JourneeTypeService#create(JourneeType)",
                    "a day template reaches the grid only through apply(), which is refused"),
            Map.entry("JourneeTypeService#update(long,JourneeType)", "same"),
            Map.entry("JourneeTypeService#delete(long)", "same — and the timeslots it produced stay"),
            Map.entry("JourneeTypeService#setCalendrier(List)", "the calendar reaches the grid only through apply()"),
            Map.entry("JourneeTypeService#reconnaitre()", "rewrites the templates from the grid, never the grid"),
            Map.entry(
                    "JourneeTypeService#importer(List,List)",
                    "a scenario's own section, and a scenario import is refused under any freeze"),
            Map.entry(
                    "EmplacementService#update(String,Emplacement)",
                    "renaming or moving a location: the freeze covers its creation and deletion only"),
            Map.entry(
                    "AnimateurService#create(Animateur)",
                    "a new fiche is not a retouch: an animateur arrives with their competences"),
            Map.entry("AnimateurService#delete(String)", "the people's data stay open, the roster with them"),
            Map.entry("AnimateurService#regenerateToken(String)", "a credential, not the referential"),
            Map.entry("AnimateurService#regenerateAbonnementToken(String)", "same"),
            Map.entry(
                    "ReferenceDataService#createContrainteAdHoc(ContrainteAdHoc)",
                    "the ad hoc adjustments stay open: they are the late phase's own gesture"),
            Map.entry("ReferenceDataService#writeContrainteAdHoc(ContrainteAdHoc)", "same"),
            Map.entry("ReferenceDataService#createContraintesAdHoc(List)", "same"),
            Map.entry("ReferenceDataService#deleteContrainteAdHoc(String)", "same"),
            Map.entry(
                    "ReferenceDataService#writeVerrouillage(VerrouillagePlanning)",
                    "a planning lock freezes seats, not fiches: another object, open under a freeze"),
            Map.entry("ReferenceDataService#createVerrouillage(VerrouillagePlanning)", "same"),
            Map.entry("ReferenceDataService#deleteVerrouillage(String)", "same"),
            Map.entry(
                    "ReferenceDataService#updateParametresLegaux(ParametresLegaux)",
                    "the settings are not one of the four families"),
            Map.entry("ReferenceDataService#updateParametresSolveur(ParametresSolveur)", "same"),
            Map.entry("ReferenceDataService#importParametresSolveur(ParametresSolveur)", "same"),
            Map.entry("ReferenceDataService#updateParametresNotifications(ParametresNotifications)", "same"),
            Map.entry("ReferenceDataService#updateParametresQualite(ParametresQualite)", "same"),
            Map.entry("ReferenceDataService#setContrainteActive(String,boolean,WeightChangeOrigin)", "same"),
            Map.entry("ReferenceDataService#setConstraintWeight(String,Integer,WeightChangeOrigin)", "same"),
            Map.entry(
                    "ReferenceDataService#recordInheritedDosage(String)",
                    "a history line on the settings a duplicated edition inherited, not one of the four families"));

    @Test
    void everyWriteMethodOfAGuardedServiceIsClassified() {
        List<String> unclassified = new ArrayList<>();
        for (Method method : methods()) {
            String key = key(method);
            int places = (method.isAnnotationPresent(RefusedWhileFrozen.class) ? 1 : 0)
                    + (CHECKED_IN_BODY.containsKey(key) ? 1 : 0)
                    + (DELEGATES.containsKey(key) ? 1 : 0)
                    + (OPEN.containsKey(key) ? 1 : 0)
                    + (READ.matcher(method.getName()).matches() ? 1 : 0);
            if (places != 1) {
                unclassified.add(key + " (" + places + ")");
            }
        }
        assertThat(unclassified)
                .as("every method of a guarded service is annotated @RefusedWhileFrozen, checked in its body,"
                        + " a delegation, open with its reason, or a read — and exactly one of them")
                .isEmpty();
    }

    @Test
    void theMethodsCheckedInTheirBodyCallTheGuard() throws IOException {
        List<String> unguarded = new ArrayList<>();
        for (Map.Entry<String, String> entry : CHECKED_IN_BODY.entrySet()) {
            String classe = entry.getKey().substring(0, entry.getKey().indexOf('#'));
            String methode = name(entry.getKey());
            String source = source(classe);
            boolean guarded =
                    bodies(source, entry.getValue()).stream().anyMatch(body -> body.contains("gel.refuseIfFrozen("));
            boolean reached = methode.equals(entry.getValue())
                    || bodies(source, methode).stream().anyMatch(body -> body.contains(entry.getValue() + "("));
            if (!guarded || !reached) {
                unguarded.add(entry.getKey());
            }
        }
        assertThat(unguarded)
                .as("methods said to check the freeze in their body, that do not")
                .isEmpty();
    }

    @Test
    void eachDelegationReachesAClassifiedMethod() throws IOException {
        Set<String> classified =
                methods().stream().map(GelReferentielStructuralTest::key).collect(Collectors.toSet());
        List<String> broken = new ArrayList<>();
        for (Map.Entry<String, String> entry : DELEGATES.entrySet()) {
            String classe = entry.getKey().substring(0, entry.getKey().indexOf('#'));
            String cible = name(entry.getValue());
            boolean calls = bodies(source(classe), name(entry.getKey())).stream()
                    .anyMatch(body ->
                            Pattern.compile("\\b" + cible + "\\(").matcher(body).find());
            if (!classified.contains(entry.getValue()) || !calls) {
                broken.add(entry.getKey() + " → " + entry.getValue());
            }
        }
        assertThat(broken)
                .as("delegations naming a method that is not classified, or not called")
                .isEmpty();
    }

    /** An interceptor on a private, static or final method would never run. */
    @Test
    void annotatedMethodsCanBeIntercepted() {
        List<String> unreachable = Stream.concat(methods().stream(), declared(PlanningPersistenceService.class))
                .filter(method -> method.isAnnotationPresent(RefusedWhileFrozen.class))
                .filter(method -> (method.getModifiers() & (Modifier.PRIVATE | Modifier.STATIC | Modifier.FINAL)) != 0)
                .map(GelReferentielStructuralTest::key)
                .toList();
        assertThat(unreachable).isEmpty();
        assertThat(Arrays.stream(GUARDED.get(0).getDeclaredMethods()))
                .as("the scan sees the annotation at run time")
                .anyMatch(method -> method.isAnnotationPresent(RefusedWhileFrozen.class));
    }

    /**
     * A call on {@code this} bypasses the interceptor: an annotated method
     * called from inside its own class would write a frozen family unrefused —
     * unless the caller's own guard already covers every family the callee
     * names. An unguarded caller never does; an annotated one does when its
     * families include the callee's (every family, for an empty list).
     */
    @Test
    void noAnnotatedMethodIsCalledOnThis() throws IOException {
        List<String> selfCalls = new ArrayList<>();
        for (Class<?> classe : GUARDED) {
            List<Method> all = Arrays.stream(classe.getDeclaredMethods())
                    .filter(method -> !method.isSynthetic() && !Modifier.isStatic(method.getModifiers()))
                    .toList();
            List<Method> annotated = all.stream()
                    .filter(method -> method.isAnnotationPresent(RefusedWhileFrozen.class))
                    .toList();
            String source = withoutComments(source(classe.getSimpleName()));
            for (Method caller : all) {
                Set<ReferentialFamily> guard = families(caller);
                String body = body(source, caller);
                for (Method callee : annotated) {
                    if (callee.equals(caller)) {
                        continue;
                    }
                    Matcher appel = Pattern.compile("(?<![\\w.#])(?:this\\.)?" + callee.getName() + "\\s*\\(")
                            .matcher(body);
                    if (appel.find() && (guard == null || !guard.containsAll(families(callee)))) {
                        selfCalls.add(key(caller) + " → " + key(callee));
                    }
                }
            }
        }
        assertThat(selfCalls)
                .as("annotated methods called on this from a method whose own guard does not cover their families,"
                        + " where the interceptor never runs")
                .isEmpty();
    }

    /** The families a method's guard refuses, every one for an empty list; {@code null} when it has none. */
    private static Set<ReferentialFamily> families(Method method) {
        RefusedWhileFrozen guard = method.getAnnotation(RefusedWhileFrozen.class);
        if (guard == null) {
            return null;
        }
        return guard.value().length == 0
                ? EnumSet.allOf(ReferentialFamily.class)
                : EnumSet.copyOf(Arrays.asList(guard.value()));
    }

    /**
     * A solve lands its plan through {@code PlanningPersistenceService.persistAfterSolve},
     * whose {@code upsertReferenceData} rewrites the stands, timeslots and
     * competences the problem carries. When the problem was built from the
     * edition that rewrites nothing the freeze protects; when the caller
     * supplied it, it overwrites them. So every door through which a supplied
     * problem enters is refused under any freeze, and a new caller of the
     * landing has to be classified here.
     */
    @Test
    void aSolveOnAProblemTheCallerSuppliedIsRefusedUnderAnyFreeze() throws IOException, NoSuchMethodException {
        // The landing: upsertReferenceData runs inside persist, whose production doors are persistAfterSolve —
        // called by the pipeline alone — and persist(PlanningEvenement), a seam only the tests call.
        String persistence = withoutComments(source("PlanningPersistenceService"));
        assertThat(callers("\\bupsertReferenceData\\s*\\(")).containsExactly("PlanningPersistenceService.java");
        assertThat(bodies(persistence, "persist"))
                .filteredOn(body -> body.contains("upsertReferenceData("))
                .hasSize(1);
        assertThat(callers("\\bpersistAfterSolve\\s*\\("))
                .containsExactlyInAnyOrder("PlanningPersistenceService.java", "SolvePipeline.java");
        assertThat(callers("\\.persist\\s*\\("))
                .as("persist(PlanningEvenement) is a test seam")
                .isEmpty();

        // Every pipeline entry handed a ready-made problem is either refused, or reached only from one that is.
        Map<String, String> suppliedProblem = Map.of(
                "SolvePipeline#execute(PlanningEvenement,Long)",
                "refused: POST /api/solve",
                "SolvePipeline#execute(String,PlanningEvenement,SolveBudget,Consumer,BooleanSupplier)",
                "reached only from SolverJobTasks#solve, itself only from SolverJobService#submitSolve, refused");
        List<String> entries = declared(dev.sylvain.planning.service.solve.SolvePipeline.class)
                .filter(method -> Arrays.asList(method.getParameterTypes())
                        .contains(dev.sylvain.planning.domain.PlanningEvenement.class))
                .map(GelReferentielStructuralTest::key)
                .toList();
        assertThat(entries).containsExactlyInAnyOrderElementsOf(suppliedProblem.keySet());
        assertRefusedUnderAnyFreeze(dev.sylvain.planning.service.solve.SolvePipeline.class.getMethod(
                "execute", dev.sylvain.planning.domain.PlanningEvenement.class, Long.class));
        assertRefusedUnderAnyFreeze(dev.sylvain.planning.service.solve.SolverJobService.class.getMethod(
                "submitSolve", dev.sylvain.planning.domain.PlanningEvenement.class, Long.class));

        // The job overload: its one caller builds the task of a supplied problem, and that task is built only
        // by the refused submission — the refusal happens at the request, never inside the job.
        String tasks = withoutComments(source("SolverJobTasks"));
        assertThat(Pattern.compile("pipeline\\.execute\\(\\s*job\\.getEditionNom\\(\\),\\s*problem\\b")
                        .matcher(bodies(tasks, "solve").get(0))
                        .find())
                .isTrue();
        assertThat(callers("\\btasks\\.solve\\s*\\(")).containsExactly("SolverJobService.java");
        assertThat(bodies(withoutComments(source("SolverJobService")), "submitSolve"))
                .anyMatch(body -> body.contains("tasks.solve("));
    }

    private static void assertRefusedUnderAnyFreeze(Method method) {
        RefusedWhileFrozen guard = method.getAnnotation(RefusedWhileFrozen.class);
        assertThat(guard).as("%s is refused under a freeze", method).isNotNull();
        assertThat(guard.value()).as("%s: under any freeze", method).isEmpty();
        assertThat(method.getModifiers() & (Modifier.PRIVATE | Modifier.STATIC | Modifier.FINAL))
                .as("%s can be intercepted", method)
                .isZero();
    }

    /** The source files where {@code call} (a regex) matches, comments aside. */
    private static List<String> callers(String call) throws IOException {
        Pattern appel = Pattern.compile(call);
        List<String> files = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(SOURCES)) {
            for (Path file :
                    paths.filter(path -> path.toString().endsWith(".java")).toList()) {
                if (appel.matcher(withoutComments(Files.readString(file))).find()) {
                    files.add(file.getFileName().toString());
                }
            }
        }
        return files;
    }

    /** The reset replaces the whole edition: refused under any freeze, like a scenario import. */
    @Test
    void theResetIsRefusedUnderAnyFreeze() throws NoSuchMethodException {
        RefusedWhileFrozen reset = PlanningPersistenceService.class
                .getDeclaredMethod("clearDatabase")
                .getAnnotation(RefusedWhileFrozen.class);
        assertThat(reset).isNotNull();
        assertThat(reset.value()).as("every family").isEmpty();
        RefusedWhileFrozen scenario = ReferenceDataService.class
                .getDeclaredMethod("importFromPlanning", dev.sylvain.planning.domain.PlanningEvenement.class)
                .getAnnotation(RefusedWhileFrozen.class);
        assertThat(scenario).isNotNull();
        assertThat(scenario.value()).as("every family").isEmpty();
    }

    /**
     * The two in-transaction grid writes stay open under a freeze because the
     * consigne is their only caller; a second caller would be a way round the
     * freeze.
     */
    @Test
    void consignesOnlyReachTheGridThroughTheirOwnDoor() throws IOException {
        Set<String> autorises = Set.of("ConsigneService.java", "ReferenceDataService.java", "CreneauService.java");
        List<String> ailleurs = new ArrayList<>();
        try (Stream<Path> files = Files.walk(SOURCES)) {
            for (Path file :
                    files.filter(path -> path.toString().endsWith(".java")).toList()) {
                if (!autorises.contains(file.getFileName().toString())
                        && opensTheGridDoor(withoutComments(Files.readString(file)))) {
                    ailleurs.add(file.getFileName().toString());
                }
            }
        }
        assertThat(ailleurs).isEmpty();
        assertThat(opensTheGridDoor(withoutComments(source("ConsigneService")))).isTrue();
        // The variable holding the connection may be named anything.
        assertThat(opensTheGridDoor("scope.write(\"x\", tx -> referenceDataService.deleteCreneaux(tx, ids));"))
                .isTrue();
        assertThat(opensTheGridDoor("try (Connection c = source.getConnection()) { reference.writeCreneau(c, x); }"))
                .isTrue();
        assertThat(opensTheGridDoor("CreneauService grille; scope.write(\"x\", (cx) -> grille.create(cx, y));"))
                .isTrue();
        // The guarded forms are not the door, two arguments or not.
        assertThat(opensTheGridDoor("scope.write(\"x\", tx -> referenceDataService.deleteCreneaux(ids));"))
                .isFalse();
        assertThat(opensTheGridDoor("referenceDataService.writeCreneau(id, creneau);"))
                .isFalse();
    }

    /**
     * Whether {@code source} calls one of the in-transaction grid writes — a
     * call whose first argument is a connection: a variable declared
     * {@code Connection}, or the parameter of a lambda handed to a
     * {@code JdbcEditionScope}, whatever it is named.
     */
    private static boolean opensTheGridDoor(String source) {
        Set<String> connexions = new TreeSet<>();
        Matcher typees = Pattern.compile("\\bConnection\\s+(\\w+)").matcher(source);
        while (typees.find()) {
            connexions.add(typees.group(1));
        }
        Matcher lambdas = Pattern.compile("\\b(?:scope|\\w*[sS]cope)\\.\\w+\\([^;]*?\\(?\\s*(\\w+)\\s*\\)?\\s*->")
                .matcher(source);
        while (lambdas.find()) {
            connexions.add(lambdas.group(1));
        }
        Matcher portees = Pattern.compile("\\bJdbcEditionScope\\s+(\\w+)").matcher(source);
        while (portees.find()) {
            Matcher lambda = Pattern.compile("\\b" + portees.group(1) + "\\.\\w+\\([^;]*?\\(?\\s*(\\w+)\\s*\\)?\\s*->")
                    .matcher(source);
            while (lambda.find()) {
                connexions.add(lambda.group(1));
            }
        }
        if (connexions.isEmpty()) {
            return false;
        }
        String premierArgument = "\\(\\s*(?:" + String.join("|", connexions) + ")\\s*,";
        if (Pattern.compile("\\b(?:writeCreneau|deleteCreneaux|deleteInBulk)" + premierArgument)
                .matcher(source)
                .find()) {
            return true;
        }
        Matcher variables = Pattern.compile("\\bCreneauService\\s+(\\w+)").matcher(source);
        while (variables.find()) {
            if (Pattern.compile("\\b" + variables.group(1) + "\\.create" + premierArgument)
                    .matcher(source)
                    .find()) {
                return true;
            }
        }
        return false;
    }

    /** A classification naming a method that no longer exists reads as a decision; it is stale. */
    @Test
    void theListsNameExistingMethods() {
        Set<String> keys =
                methods().stream().map(GelReferentielStructuralTest::key).collect(Collectors.toSet());
        Set<String> stale = new TreeSet<>(CHECKED_IN_BODY.keySet());
        stale.addAll(DELEGATES.keySet());
        stale.addAll(OPEN.keySet());
        stale.removeAll(keys);
        assertThat(stale).isEmpty();
    }

    /* -------------------------------- helpers -------------------------------- */

    private static List<Method> methods() {
        return GUARDED.stream().flatMap(GelReferentielStructuralTest::declared).toList();
    }

    private static Stream<Method> declared(Class<?> classe) {
        return Arrays.stream(classe.getDeclaredMethods())
                .filter(method -> !method.isSynthetic() && !method.isBridge())
                .filter(method -> !Modifier.isPrivate(method.getModifiers()))
                .filter(method -> !Modifier.isStatic(method.getModifiers()));
    }

    private static String key(Method method) {
        return method.getDeclaringClass().getSimpleName() + "#" + method.getName() + "("
                + Arrays.stream(method.getParameterTypes())
                        .map(Class::getSimpleName)
                        .collect(Collectors.joining(","))
                + ")";
    }

    private static String name(String key) {
        return key.substring(key.indexOf('#') + 1, key.indexOf('('));
    }

    private static final Map<String, String> SOURCES_BY_CLASS = new LinkedHashMap<>();

    private static String source(String classe) throws IOException {
        String cached = SOURCES_BY_CLASS.get(classe);
        if (cached != null) {
            return cached;
        }
        Path file;
        try (Stream<Path> files = Files.walk(SOURCES)) {
            file = files.filter(path -> path.getFileName().toString().equals(classe + ".java"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("class not found: " + classe));
        }
        String source = Files.readString(file);
        SOURCES_BY_CLASS.put(classe, source);
        return source;
    }

    /** The body of {@code method} in {@code source}, told from its overloads by its parameter types. */
    private static String body(String source, Method method) {
        String parameters = Arrays.stream(method.getParameterTypes())
                .map(Class::getSimpleName)
                .collect(Collectors.joining(","));
        return declarations(source, method.getName()).stream()
                .filter(declaration -> declaration.parameters().equals(parameters))
                .map(Declaration::body)
                .findFirst()
                .orElseThrow(() -> new AssertionError("declaration not found: " + key(method)));
    }

    /** The bodies of every method {@code method} declared in {@code source}, overloads included. */
    private static List<String> bodies(String source, String method) {
        List<String> bodies =
                declarations(source, method).stream().map(Declaration::body).toList();
        assertThat(bodies).as("method not found: %s", method).isNotEmpty();
        return bodies;
    }

    /** A method as declared: its parameter types by simple name, erased, and its body. */
    private record Declaration(String parameters, String body) {}

    private static List<Declaration> declarations(String source, String method) {
        List<Declaration> declarations = new ArrayList<>();
        Matcher declaration = Pattern.compile(
                        "\\n    (?=\\S)(?:@\\w+(?:\\([^)]*\\))?\\s+)*(?:(?:public|protected|private|static|final)\\s+)*"
                                + "[\\w<>, .?\\[\\]]+ " + Pattern.quote(method) + "\\(")
                .matcher(source);
        while (declaration.find()) {
            int index = declaration.end();
            int depth = 1;
            while (depth > 0) {
                char c = source.charAt(index++);
                depth += c == '(' ? 1 : c == ')' ? -1 : 0;
            }
            String parameters = parameterTypes(source.substring(declaration.end(), index - 1));
            int open = source.indexOf('{', index);
            int end = open + 1;
            depth = 1;
            while (depth > 0) {
                char c = source.charAt(end++);
                depth += c == '{' ? 1 : c == '}' ? -1 : 0;
            }
            declarations.add(new Declaration(parameters, source.substring(open, end)));
        }
        return declarations;
    }

    /** {@code "Connection connection, List<Creneau> creneaux"} → {@code "Connection,List"}. */
    private static String parameterTypes(String declared) {
        String sansAnnotations =
                declared.replaceAll("@[\\w.]+(?:\\([^)]*\\))?", "").replaceAll("\\bfinal\\s+", "");
        String erased = sansAnnotations;
        String previous;
        do {
            previous = erased;
            erased = erased.replaceAll("<[^<>]*>", "");
        } while (!erased.equals(previous));
        if (erased.isBlank()) {
            return "";
        }
        return Arrays.stream(erased.split(","))
                .map(parameter -> parameter.trim().replaceAll("\\s+", " "))
                .map(parameter ->
                        parameter.substring(0, parameter.lastIndexOf(' ')).trim())
                .map(type -> type.replace("...", "[]"))
                .map(type -> type.substring(type.lastIndexOf('.') + 1))
                .collect(Collectors.joining(","));
    }

    private static String withoutComments(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
    }
}
