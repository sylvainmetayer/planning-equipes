package dev.sylvain.planning.service.solve;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.service.JdbcEditionScope;
import dev.sylvain.planning.service.referentiel.RefusedWhileFrozen;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
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
 * No write a running solve would undo goes without its guard — and what is
 * deliberately left open while a solve runs is written down, never left to
 * omission.
 *
 * <p>The guard is {@link RefusedWhileSolving}, on the services: the REST
 * resources, the MCP tools, the bulk edit and the imports all reach the data
 * through them. The test reads every non-private, non-static method of the
 * <b>guarded services</b> — the {@code *Service} beans of
 * {@code service/referentiel} and {@code service/consigne}, found by listing
 * those folders, so a new one is in without being named; the services that
 * write the plan ({@link #PLAN_WRITERS}); and every other bean that carries
 * the annotation or calls {@code refuseIfSolving} — and asks each one to be in
 * exactly one of five places:</p>
 *
 * <ul>
 *   <li>annotated {@link RefusedWhileSolving} — the interceptor refuses the
 *       whole call before it starts;</li>
 *   <li>{@link #IMPERATIVE} — the method refuses in one branch only, or is the
 *       guard itself, and calls {@code refuseIfSolving} in its body; the call
 *       is read back in its source, and every such call in the backend must be
 *       argued here or in {@link #IMPERATIVE_ELSEWHERE};</li>
 *   <li>{@link #DELEGATES} — a facade method that writes nothing itself and
 *       hands over to a method classified here; the call is read back;</li>
 *   <li>{@link #OPEN} — not refused as a whole while a solve runs, each with
 *       its reason: a write the landing does not undo, or one that hands each
 *       row to a write classified here;</li>
 *   <li>a read, recognised by its name ({@link #READ}).</li>
 * </ul>
 *
 * <p><b>What counts as a write</b> is decided the way
 * {@code GelReferentielStructuralTest} decides it: by default, everything. A
 * method is a read only when its name says so; any other method is a write
 * until it is classified, so a new method nobody thought about fails the
 * build instead of slipping through as a read — the list is long because the
 * default is strict, not because it was drawn by hand. And a name is only a
 * claim, so it is checked: a method read by its name that writes, directly or
 * through what it calls, fails ({@link #noMethodReadByItsNameWrites}).</p>
 *
 * <p>And since an interceptor is bypassed by a call on {@code this}, an
 * annotated method is never called from inside its own class, unless by a
 * method that is itself annotated and has refused already.</p>
 */
class RefusedWhileSolvingStructuralTest {

    private static final Path SOURCES = Path.of("src/main/java/dev/sylvain/planning");

    private static final String ROOT_PACKAGE = "dev.sylvain.planning";

    /** Every {@code *Service} bean of these folders is guarded, without being named. */
    private static final List<String> SCANNED = List.of("service/referentiel", "service/consigne");

    /**
     * The services that write the plan: the seat writes, the moves, the
     * restores. {@link #noOtherClassWritesTheSeats} holds that no class
     * outside the referential and these writes {@code poste_affectation}.
     */
    private static final List<Class<?>> PLAN_WRITERS =
            List.of(PlanningPersistenceService.class, PlanSnapshotService.class, DeplacementService.class);

    /** A read says so in its name; nothing named this way may write ({@link #noMethodReadByItsNameWrites}). */
    private static final Pattern READ = Pattern.compile("^(list|find|get|count|preview|resolve|exemple|export|volumes"
            + "|labels|validateIds|calendrier|etat|ninja|controler|gridAnomalies|diagnose|fenetresRepas|snapshot"
            + "|typologieNinja|abonnementToken|idBy|idsBy|typologyIdsBy|report|joursEvenement|on[A-Z]"
            + "|standsOnOwnHours|apercu|byDate|creneauxAjoutes|datesSousConsigne|indicateurs|lignesJourneesModifiees"
            + "|preselection|check|fenetre|impact|is[A-Z]|libelle|pendingDemandes|decisionsNonCommuniquees|simulate"
            + "|absentCount|suggestions|between|build|configuredWeight|constraintWeights|disabledContraintes"
            + "|effective|qualite|solverBudgetBounds|load|lastPublication|assemblerPlanning|for[A-Z]|csv|zip"
            + "|stateOf|view|refuseIfFrozen).*");

    /** Method → why it keeps an explicit {@code refuseIfSolving} call rather than the annotation. */
    private static final Map<String, String> IMPERATIVE = Map.ofEntries(
            Map.entry(
                    "StandService#compactHoraires(boolean)",
                    "refuses only when it applies: a dry run writes nothing and may be read during a solve"),
            Map.entry(
                    "PlanningPersistenceService#refuseIfSolving()",
                    "the guard itself, asked on its own by PlanningWhatIf#prepareSeating, where no interceptor"
                            + " can run (see IMPERATIVE_ELSEWHERE)"));

    /**
     * The explicit calls outside the guarded services, as {@code Class#method},
     * each with its reason: no interceptor can run there.
     */
    private static final Map<String, String> IMPERATIVE_ELSEWHERE = Map.ofEntries(
            Map.entry(
                    "PlanningWhatIf#prepareSeating",
                    "a private method of a class PlanningService's constructor builds, not a CDI bean — no"
                            + " interceptor can run on it; first of the pre-checks of a seat write, so nothing is"
                            + " looked up or scored, and skipped when no persistence is wired (the database-less"
                            + " tests)"),
            Map.entry("RefusedWhileSolvingInterceptor#refuseWhileSolving", "the interceptor itself"));

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
            Map.entry("ReferenceDataService#deleteTypologie(String)", "TypologieService#delete(String)"),
            Map.entry(
                    "ReferenceDataService#createContrainteAdHoc(ContrainteAdHoc)",
                    "ContrainteAdHocService#create(ContrainteAdHoc)"),
            Map.entry(
                    "ReferenceDataService#writeContrainteAdHoc(ContrainteAdHoc)",
                    "ContrainteAdHocService#create(ContrainteAdHoc)"),
            Map.entry(
                    "ReferenceDataService#createContraintesAdHoc(Connection,List)",
                    "ContrainteAdHocService#createAll(Connection,List)"),
            Map.entry("ReferenceDataService#deleteContrainteAdHoc(String)", "ContrainteAdHocService#delete(String)"),
            Map.entry(
                    "ReferenceDataService#createVerrouillage(VerrouillagePlanning)",
                    "VerrouillageService#create(VerrouillagePlanning)"),
            Map.entry(
                    "ReferenceDataService#writeVerrouillage(VerrouillagePlanning)",
                    "ReferenceDataService#createVerrouillage(VerrouillagePlanning)"),
            Map.entry("ReferenceDataService#deleteVerrouillage(String)", "VerrouillageService#delete(String)"),
            Map.entry(
                    "ReferenceDataService#updateParametresLegaux(ParametresLegaux)",
                    "ParametresService#updateLegaux(ParametresLegaux)"),
            Map.entry(
                    "ReferenceDataService#updateParametresSolveur(ParametresSolveur)",
                    "ParametresService#updateSolveur(ParametresSolveur)"),
            Map.entry(
                    "ReferenceDataService#importParametresSolveur(ParametresSolveur)",
                    "ParametresService#importSolveur(ParametresSolveur)"),
            Map.entry(
                    "ReferenceDataService#updateParametresNotifications(ParametresNotifications)",
                    "ParametresService#updateNotifications(ParametresNotifications)"),
            Map.entry(
                    "ReferenceDataService#updateParametresQualite(ParametresQualite)",
                    "ParametresService#updateQualite(ParametresQualite)"),
            Map.entry(
                    "ReferenceDataService#updateContactOrganisation(ContactOrganisation)",
                    "ParametresService#updateContactOrganisation(ContactOrganisation)"),
            Map.entry(
                    "ReferenceDataService#setContrainteActive(String,boolean,WeightChangeOrigin)",
                    "ParametresService#setContrainteActive(String,boolean,WeightChangeOrigin)"),
            Map.entry(
                    "ReferenceDataService#setConstraintWeight(String,Integer,WeightChangeOrigin)",
                    "ParametresService#setConstraintWeight(String,Integer,WeightChangeOrigin)"),
            Map.entry(
                    "ReferenceDataService#recordInheritedDosage(String)",
                    "ParametresService#recordInheritedDosage(String)"));

    /** The landing rewrites none of these: a reason shared by several entries below. */
    private static final String NEW_ROW =
            "a new row no solve read: the landing upserts what its problem names and removes nothing else";

    private static final String NOT_LANDED = "not among what the landing writes (stands, timeslots, animateurs,"
            + " their competences and off days, the seats): the next solve reads it, accepted with a warning over MCP";

    /** Not refused as a whole while a solve runs, each with its reason. */
    private static final Map<String, String> OPEN = Map.ofEntries(
            Map.entry("AnimateurService#create(Animateur)", NEW_ROW),
            Map.entry(
                    "AnimateurService#regenerateToken(String)",
                    "a credential: the landing writes an animateur's identity, never a token"),
            Map.entry("AnimateurService#regenerateAbonnementToken(String)", "same"),
            Map.entry("StandService#create(Stand)", NEW_ROW),
            Map.entry("StandService#create(Connection,Stand)", NEW_ROW),
            Map.entry("CreneauService#create(Creneau)", NEW_ROW + "; no seat stands on it yet"),
            Map.entry(
                    "CreneauService#create(Connection,Creneau)",
                    "same, inside the consigne's transaction — ConsigneService#poser, refused as a whole"),
            Map.entry("EmplacementService#create(Emplacement)", NOT_LANDED),
            Map.entry("EmplacementService#create(Connection,Emplacement)", NOT_LANDED),
            Map.entry("EmplacementService#update(String,Emplacement)", NOT_LANDED),
            Map.entry("EmplacementService#delete(String)", NOT_LANDED),
            Map.entry("TypologieService#create(TypologieItem)", NOT_LANDED),
            Map.entry("TypologieService#create(Connection,TypologieItem)", NOT_LANDED),
            Map.entry("TypologieService#update(String,TypologieItem)", NOT_LANDED),
            Map.entry("TypologieService#importer(TypologieItem)", NOT_LANDED),
            Map.entry(
                    "TypologieService#delete(String)",
                    NOT_LANDED + "; refused while a stand or an animateur uses it, so the landing never names one"),
            Map.entry("ContrainteAdHocService#create(ContrainteAdHoc)", NOT_LANDED),
            Map.entry("ContrainteAdHocService#createAll(Connection,List)", NOT_LANDED),
            Map.entry("ContrainteAdHocService#delete(String)", NOT_LANDED),
            Map.entry("VerrouillageService#create(VerrouillagePlanning)", NOT_LANDED),
            Map.entry("VerrouillageService#delete(String)", NOT_LANDED),
            Map.entry("ParametresService#updateLegaux(ParametresLegaux)", NOT_LANDED),
            Map.entry("ParametresService#updateSolveur(ParametresSolveur)", NOT_LANDED),
            Map.entry("ParametresService#importSolveur(ParametresSolveur)", NOT_LANDED),
            Map.entry("ParametresService#updateNotifications(ParametresNotifications)", NOT_LANDED),
            Map.entry("ParametresService#updateQualite(ParametresQualite)", NOT_LANDED),
            Map.entry("ParametresService#updateContactOrganisation(ContactOrganisation)", NOT_LANDED),
            Map.entry("ParametresService#setContrainteActive(String,boolean,WeightChangeOrigin)", NOT_LANDED),
            Map.entry("ParametresService#setConstraintWeight(String,Integer,WeightChangeOrigin)", NOT_LANDED),
            Map.entry("ParametresService#recordInheritedDosage(String)", NOT_LANDED),
            Map.entry(
                    "GelReferentielService#freeze(ReferentialFamily)",
                    "the freeze refuses the writes to come, it changes no data a solve reads"),
            Map.entry("GelReferentielService#lift(ReferentialFamily)", "same"),
            Map.entry(
                    "JourneeTypeService#create(JourneeType)",
                    "a day template reaches the grid only through apply(), which is refused"),
            Map.entry("JourneeTypeService#update(long,JourneeType)", "same"),
            Map.entry("JourneeTypeService#delete(long)", "same"),
            Map.entry("JourneeTypeService#setCalendrier(List)", "same, for the calendar of the day templates"),
            Map.entry("JourneeTypeService#reconnaitre()", "rewrites the templates from the grid, never the grid"),
            Map.entry("JourneeTypeService#importer(List,List)", "templates and their calendar only, as above"),
            Map.entry(
                    "ReferentielCsvImportService#apply(ImportTarget,ReferentielCsvImportRequest)",
                    "hands each row to the write of its own family, classified here: a stand edit"
                            + " (StandService#update) or a timeslot file (CreneauService#importer) is refused there;"
                            + " game categories, locations and new stands are not"),
            Map.entry("ReferentielCsvExportService#writeEntries(Set,ZipOutputStream,String)", "writes a ZIP stream"),
            Map.entry(
                    "CreneauGridService#validate(List,List,List,ParametresLegaux,List,List)",
                    "a read whose name says check: reports on a grid, writes nothing"),
            Map.entry(
                    "ConsigneService#savePrereglage(PrereglageConsigne)", "a preset is read by the consigne form only"),
            Map.entry("ConsigneService#deletePrereglage(String)", "same"),
            Map.entry(
                    "PlanningPersistenceService#persistAfterSolve(PlanningEvenement,Long)",
                    "the landing itself, written by the job that holds the solver"),
            Map.entry(
                    "PlanningPersistenceService#persist(PlanningEvenement)",
                    "a test seam: GelReferentielStructuralTest holds that production never calls it"),
            Map.entry(
                    "PlanSnapshotService#capture(String,boolean)",
                    "copies the persisted plan into a snapshot: writes no seat, and no solve reads a snapshot"),
            Map.entry("PlanSnapshotService#captureBeforeSolve()", "the solve's own snapshot, taken as it starts"),
            Map.entry("PlanSnapshotService#capturePubliee(String)", "the publication's snapshot, as capture"),
            Map.entry("PlanSnapshotService#delete(long)", "no solve reads a snapshot"),
            Map.entry("PlanSnapshotService#recordScore(long,String)", "a score noted on a snapshot, as above"),
            Map.entry(
                    "DemandeEchangeService#submit(String,List)",
                    "a swap request is stored, the plan untouched: only accept() moves seats"),
            Map.entry("DemandeEchangeService#cancel(String,String)", "same: a step of the request, the plan untouched"),
            Map.entry("DemandeEchangeService#acceptByTarget(String,String)", "same"),
            Map.entry("DemandeEchangeService#declineByTarget(String,String)", "same"),
            Map.entry("DemandeEchangeService#refuse(String,String)", "same"),
            Map.entry("DemandeEchangeService#openFoire(FenetreFoire)", "the foire's window is read by no solve"),
            Map.entry(
                    "DemandeEchangeService#markAsCommunicated(Collection,Instant)",
                    "a publication's bookkeeping, read by no solve"),
            Map.entry(
                    "TeammateRequestService#request(String,List)",
                    "a pending request writes no exception: only its validation does"),
            Map.entry(
                    "TeammateRequestService#validate(String)",
                    "writes its grouped arrival through the ad hoc path, " + NOT_LANDED),
            Map.entry("TeammateRequestService#setAside(String,String)", "a pending request had written no exception"),
            Map.entry(
                    "JourJService#cancelAbsence(String,LocalDate,Long)",
                    "removes the exceptions the absence wrote and hands no seat back: " + NOT_LANDED));

    @Test
    void everyWriteMethodOfAGuardedServiceIsClassified() throws IOException {
        List<String> unclassified = new ArrayList<>();
        for (Method method : methods()) {
            String key = key(method);
            int places = (method.isAnnotationPresent(RefusedWhileSolving.class) ? 1 : 0)
                    + (IMPERATIVE.containsKey(key) ? 1 : 0)
                    + (DELEGATES.containsKey(key) ? 1 : 0)
                    + (OPEN.containsKey(key) ? 1 : 0)
                    + (READ.matcher(method.getName()).matches() ? 1 : 0);
            if (places != 1) {
                unclassified.add(key + " (" + places + ")");
            }
        }
        assertThat(unclassified)
                .as("every method of a guarded service is annotated @RefusedWhileSolving, imperative with its"
                        + " reason, a delegation, open with its reason, or a read — and exactly one of them")
                .isEmpty();
    }

    /**
     * A name is a claim: a method of a guarded service read by its name
     * ({@link #READ}) writes nothing — directly, through a method of its own
     * class it calls, or through a write of another class it calls on one of
     * its fields. A {@code load…}, {@code build…}, {@code on…} or
     * {@code check…} that writes would otherwise pass as a read, unguarded and
     * unargued.
     *
     * <p>A write is recognised by what the backend writes with
     * ({@link #WRITE_MARKER}, {@link #WRITE_PRIMITIVES}), or by an annotation
     * that only a write carries ({@code @RefusedWhileSolving},
     * {@code @RefusedWhileFrozen}).</p>
     */
    @Test
    void noMethodReadByItsNameWrites() throws IOException {
        List<String> writing = new ArrayList<>();
        for (Method method : methods()) {
            if (READ.matcher(method.getName()).matches()) {
                String source =
                        withoutComments(source(method.getDeclaringClass().getSimpleName()));
                if (bodyWrites(method.getDeclaringClass(), body(source, method))) {
                    writing.add(key(method));
                }
            }
        }
        assertThat(writing)
                .as("methods read by their name (READ) that write: rename them, or classify them as the writes they"
                        + " are")
                .isEmpty();
    }

    /** The detection {@link #noMethodReadByItsNameWrites} relies on, on bodies written for it. */
    @Test
    void aWriteIsRecognisedByItsMarkers() {
        assertThat(WRITE_MARKER.matcher("{ scope.write(\"x\", c -> {}); }").find())
                .isTrue();
        assertThat(WRITE_MARKER.matcher("{ ps.executeUpdate(); }").find()).isTrue();
        assertThat(WRITE_MARKER.matcher("{ changeTracker.markModified(); }").find())
                .isTrue();
        assertThat(WRITE_MARKER
                        .matcher("{ prepare(\"\"\"\n    DELETE FROM stand WHERE edition_id = ?\"\"\"); }")
                        .find())
                .isTrue();
        assertThat(WRITE_MARKER
                        .matcher("{ prepare(\"UPDATE stand SET nom = ?\"); }")
                        .find())
                .isTrue();
        assertThat(WRITE_MARKER
                        .matcher("{ return scope.read(\"x\", c -> c.prepareStatement(\"SELECT 1\")); }")
                        .find())
                .isFalse();
        assertThat(WRITE_MARKER
                        .matcher("{ throw new Invalid(\"Update refused\"); }")
                        .find())
                .isFalse();
    }

    @Test
    void theImperativeMethodsCallTheGuard() throws IOException {
        List<String> unguarded = new ArrayList<>();
        for (String key : IMPERATIVE.keySet()) {
            String source = withoutComments(source(className(key)));
            if (bodies(source, name(key)).stream().noneMatch(body -> body.contains("refuseIfSolving("))) {
                unguarded.add(key);
            }
        }
        for (String key : IMPERATIVE_ELSEWHERE.keySet()) {
            String source = withoutComments(source(className(key)));
            if (bodies(source, key.substring(key.indexOf('#') + 1)).stream()
                    .noneMatch(body -> body.contains("refuseIfSolving("))) {
                unguarded.add(key);
            }
        }
        assertThat(unguarded)
                .as("methods said to call refuseIfSolving in their body, that do not")
                .isEmpty();
    }

    /**
     * An explicit call is the exception, and argued: anywhere in the backend,
     * a {@code refuseIfSolving()} written by hand sits in a method named in
     * {@link #IMPERATIVE} or {@link #IMPERATIVE_ELSEWHERE} — the guard's own
     * declarations aside.
     */
    @Test
    void everyExplicitCallIsArgued() throws IOException {
        Set<String> argued = new TreeSet<>();
        IMPERATIVE.keySet().forEach(key -> argued.add(key.substring(0, key.indexOf('('))));
        argued.addAll(IMPERATIVE_ELSEWHERE.keySet());
        List<String> unargued = new ArrayList<>();
        Pattern call = Pattern.compile("(?<!void )\\brefuseIfSolving\\(");
        for (Path file : javaFiles()) {
            String source = withoutComments(Files.readString(file));
            String classe = file.getFileName().toString().replace(".java", "");
            Matcher matcher = call.matcher(source);
            while (matcher.find()) {
                String enclosing = classe + "#" + enclosingMethod(source, matcher.start());
                if (!argued.contains(enclosing)) {
                    unargued.add(enclosing);
                }
            }
        }
        assertThat(unargued)
                .as("refuseIfSolving() called by hand outside the argued list: annotate the method with"
                        + " @RefusedWhileSolving, or argue the call in IMPERATIVE")
                .isEmpty();
    }

    @Test
    void eachDelegationReachesAClassifiedMethod() throws IOException {
        Set<String> classified =
                methods().stream().map(RefusedWhileSolvingStructuralTest::key).collect(Collectors.toSet());
        List<String> broken = new ArrayList<>();
        for (Map.Entry<String, String> entry : DELEGATES.entrySet()) {
            String cible = name(entry.getValue());
            boolean calls = bodies(withoutComments(source(className(entry.getKey()))), name(entry.getKey())).stream()
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

    /**
     * An interceptor runs only on a bean, and only on a method its proxy can
     * override: anywhere in the backend, the annotation on anything else would
     * be a guard that never runs.
     */
    @Test
    void annotatedMethodsCanBeIntercepted() throws IOException {
        List<String> unreachable = new ArrayList<>();
        int annotated = 0;
        for (Path file : javaFiles()) {
            if (!withoutComments(Files.readString(file)).contains("@RefusedWhileSolving")) {
                continue;
            }
            Class<?> classe = load(file);
            if (classe == RefusedWhileSolvingInterceptor.class) {
                continue;
            }
            for (Method method : classe.getDeclaredMethods()) {
                if (!method.isAnnotationPresent(RefusedWhileSolving.class)) {
                    continue;
                }
                annotated++;
                if ((method.getModifiers() & (Modifier.PRIVATE | Modifier.STATIC | Modifier.FINAL)) != 0
                        || Modifier.isFinal(classe.getModifiers())
                        || !isBean(classe)) {
                    unreachable.add(key(method));
                }
            }
        }
        assertThat(unreachable).isEmpty();
        assertThat(annotated).as("the scan sees the annotation at run time").isPositive();
    }

    /**
     * A call on {@code this} bypasses the interceptor — and so does a method
     * reference {@code this::method}, handed to a {@code forEach} or a
     * transaction: an annotated method called from inside its own class by a
     * method that is not annotated would write unrefused. A caller that is
     * annotated itself has refused already.
     */
    @Test
    void noAnnotatedMethodIsCalledOnThis() throws IOException {
        List<String> selfCalls = new ArrayList<>();
        for (Class<?> classe : guarded()) {
            List<Method> all = Arrays.stream(classe.getDeclaredMethods())
                    .filter(method -> !method.isSynthetic() && !Modifier.isStatic(method.getModifiers()))
                    .toList();
            Set<String> annotated = all.stream()
                    .filter(method -> method.isAnnotationPresent(RefusedWhileSolving.class))
                    .map(Method::getName)
                    .collect(Collectors.toCollection(TreeSet::new));
            String source = withoutComments(source(classe.getSimpleName()));
            for (Method caller : all) {
                if (caller.isAnnotationPresent(RefusedWhileSolving.class)) {
                    continue;
                }
                String body = body(source, caller);
                for (String callee : annotated) {
                    if (callsOnThis(body, callee)) {
                        selfCalls.add(key(caller) + " → " + callee);
                    }
                }
            }
        }
        assertThat(selfCalls)
                .as("annotated methods called on this from a method that is not annotated, where the interceptor"
                        + " never runs")
                .isEmpty();
    }

    /** The detection {@link #noAnnotatedMethodIsCalledOnThis} relies on, a method reference included. */
    @Test
    void aCallOnThisIsSeenAsACallOrAsAMethodReference() {
        assertThat(callsOnThis("{ delete(id); }", "delete")).isTrue();
        assertThat(callsOnThis("{ this.delete(id); }", "delete")).isTrue();
        assertThat(callsOnThis("{ ids.forEach(this::delete); }", "delete")).isTrue();
        assertThat(callsOnThis("{ ids.forEach(this :: delete); }", "delete")).isTrue();
        assertThat(callsOnThis("{ repository.delete(id); }", "delete")).isFalse();
        assertThat(callsOnThis("{ ids.forEach(repository::delete); }", "delete"))
                .isFalse();
        assertThat(callsOnThis("{ ids.forEach(this::deleteAll); }", "delete")).isFalse();
        assertThat(callsOnThis("{ undelete(id); }", "delete")).isFalse();
    }

    /**
     * The plan's seats are written by the referential (a stand or a timeslot
     * deleted takes its seats along) and by {@link #PLAN_WRITERS}; a third
     * class writing them would be outside the scan above.
     */
    @Test
    void noOtherClassWritesTheSeats() throws IOException {
        Set<String> planWriters =
                PLAN_WRITERS.stream().map(Class::getSimpleName).collect(Collectors.toSet());
        Pattern seatWrite = Pattern.compile("\\b(?:UPDATE|INSERT INTO|DELETE FROM)\\s+poste_affectation\\b");
        List<String> elsewhere = new ArrayList<>();
        for (Path file : javaFiles()) {
            String classe = file.getFileName().toString().replace(".java", "");
            boolean scanned = SCANNED.stream().anyMatch(folder -> file.startsWith(SOURCES.resolve(folder)));
            if (!scanned
                    && !planWriters.contains(classe)
                    && seatWrite.matcher(Files.readString(file)).find()) {
                elsewhere.add(classe);
            }
        }
        assertThat(elsewhere).isEmpty();
    }

    /** A classification naming a method that no longer exists reads as a decision; it is stale. */
    @Test
    void theListsNameExistingMethods() throws IOException {
        Set<String> keys =
                methods().stream().map(RefusedWhileSolvingStructuralTest::key).collect(Collectors.toSet());
        Set<String> stale = new TreeSet<>(IMPERATIVE.keySet());
        stale.addAll(DELEGATES.keySet());
        stale.addAll(OPEN.keySet());
        stale.removeAll(keys);
        assertThat(stale).isEmpty();
    }

    /* -------------------------------- helpers -------------------------------- */

    /** The guarded services: the scanned folders, the plan writers, and every bean holding the guard. */
    private static List<Class<?>> guarded() throws IOException {
        Set<Class<?>> classes = new LinkedHashSet<>();
        for (String folder : SCANNED) {
            try (Stream<Path> files = Files.list(SOURCES.resolve(folder))) {
                for (Path file : files.filter(
                                path -> path.getFileName().toString().endsWith("Service.java"))
                        .sorted()
                        .toList()) {
                    Class<?> classe = load(file);
                    if (isBean(classe)) {
                        classes.add(classe);
                    }
                }
            }
        }
        classes.addAll(PLAN_WRITERS);
        for (Path file : javaFiles()) {
            String source = withoutComments(Files.readString(file));
            if (source.contains("@RefusedWhileSolving") || source.contains("refuseIfSolving(")) {
                Class<?> classe = load(file);
                if (isBean(classe) && classe != SolverJobService.class) {
                    classes.add(classe);
                }
            }
        }
        return List.copyOf(classes);
    }

    private static List<Method> methods() throws IOException {
        List<Method> methods = new ArrayList<>();
        for (Class<?> classe : guarded()) {
            Arrays.stream(classe.getDeclaredMethods())
                    .filter(method -> !method.isSynthetic() && !method.isBridge())
                    .filter(method -> !Modifier.isPrivate(method.getModifiers()))
                    .filter(method -> !Modifier.isStatic(method.getModifiers()))
                    .forEach(methods::add);
        }
        return methods;
    }

    private static boolean isBean(Class<?> classe) {
        return classe.isAnnotationPresent(ApplicationScoped.class)
                || classe.isAnnotationPresent(Singleton.class)
                || classe.isAnnotationPresent(RequestScoped.class)
                || classe.isAnnotationPresent(Dependent.class);
    }

    private static Class<?> load(Path file) {
        String relative = SOURCES.relativize(file).toString();
        String name = ROOT_PACKAGE + "."
                + relative.substring(0, relative.length() - ".java".length()).replace('/', '.');
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException e) {
            throw new AssertionError("class not found: " + name, e);
        }
    }

    private static List<Path> javaFiles() throws IOException {
        try (Stream<Path> files = Files.walk(SOURCES)) {
            return files.filter(path -> path.toString().endsWith(".java"))
                    .sorted()
                    .toList();
        }
    }

    private static String key(Method method) {
        return method.getDeclaringClass().getSimpleName() + "#" + method.getName() + "("
                + Arrays.stream(method.getParameterTypes())
                        .map(Class::getSimpleName)
                        .collect(Collectors.joining(","))
                + ")";
    }

    private static String className(String key) {
        return key.substring(0, key.indexOf('#'));
    }

    private static String name(String key) {
        return key.substring(key.indexOf('#') + 1, key.indexOf('('));
    }

    private static final Map<String, String> SOURCES_BY_CLASS = new HashMap<>();

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

    /** The name of the method whose body holds {@code index}: the last declaration opened before it. */
    private static String enclosingMethod(String source, int index) {
        Matcher declaration = DECLARATION.matcher(source.substring(0, index));
        String name = "?";
        while (declaration.find()) {
            name = declaration.group(1);
        }
        return name;
    }

    /** A method declared at class level: four spaces of indentation, modifiers, a type, a name, a parenthesis. */
    private static final Pattern DECLARATION = Pattern.compile(
            "\\n    (?=\\S)(?:@\\w+(?:\\([^)]*\\))?\\s+)*(?:(?:public|protected|private|static|final|synchronized)\\s+)*"
                    + "(?:<[^>]+>\\s+)?[\\w<>, .?\\[\\]]+ (\\w+)\\(");

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
        Matcher declaration = Pattern.compile("\\n    (?=\\S)(?:@\\w+(?:\\([^)]*\\))?\\s+)*"
                        + "(?:(?:public|protected|private|static|final|synchronized)\\s+)*"
                        + "(?:<[^>]+>\\s+)?[\\w<>, .?\\[\\]]+ " + Pattern.quote(method) + "\\(")
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

    /**
     * What writes, read in a body: a transaction of {@code JdbcEditionScope}
     * (by the field name every service gives it), a statement executed as an
     * update, the change tracker told the referential moved, and a SQL literal
     * that inserts, updates or deletes.
     */
    private static final Pattern WRITE_MARKER = Pattern.compile("\\bscope\\.(?:write|writeAndReturn|delete)\\("
            + "|\\.execute(?:Update|Batch|LargeUpdate)\\(|\\.markModified\\("
            + "|\"\\s*(?:INSERT\\s+INTO|UPDATE\\s+\\w+\\s+SET|DELETE\\s+FROM|TRUNCATE)\\b");

    /** The methods that write although their body shows no marker: they run the caller's write. */
    private static final Map<Class<?>, Set<String>> WRITE_PRIMITIVES =
            Map.of(JdbcEditionScope.class, Set.of("write", "writeAndReturn", "delete"));

    /** {@code private static final String NAME = "INSERT …";}: a constant a body names instead of its SQL. */
    private static final Pattern STRING_CONSTANT = Pattern.compile("static final String (\\w++)\\s*+=\\s*+([^;]*+);");

    /** A method of the backend by name, every overload at once: what a call in a body designates. */
    private record Callee(Class<?> classe, String name) {}

    /** What a method writes by itself, and what it calls. */
    private record Edges(boolean writes, Set<Callee> callees) {}

    private static final Map<Callee, Edges> EDGES = new HashMap<>();

    private static final Map<Callee, Boolean> WRITES = new HashMap<>();

    /**
     * Whether {@code body}, declared in {@code classe}, writes: by itself, or
     * through any method it reaches — on {@code this}, or on a field whose
     * type is a class of the backend.
     *
     * <p>A method writes when it reaches a write: a search that ends without
     * finding one has read everything it visited to the end, so all of it is
     * remembered as not writing; a search cut short by a write remembers only
     * the path that led there.</p>
     */
    private static boolean bodyWrites(Class<?> classe, String body) throws IOException {
        Edges edges = edges(classe, List.of(body));
        if (edges.writes()) {
            return true;
        }
        Set<Callee> visited = new HashSet<>();
        for (Callee callee : edges.callees()) {
            if (reachesAWrite(callee, visited)) {
                return true;
            }
        }
        visited.forEach(callee -> WRITES.put(callee, false));
        return false;
    }

    private static boolean reachesAWrite(Callee callee, Set<Callee> visited) throws IOException {
        Boolean known = WRITES.get(callee);
        if (known != null) {
            return known;
        }
        if (!visited.add(callee)) {
            return false;
        }
        Edges edges = edges(callee);
        boolean writes = edges.writes();
        for (Iterator<Callee> next = edges.callees().iterator(); !writes && next.hasNext(); ) {
            writes = reachesAWrite(next.next(), visited);
        }
        if (writes) {
            WRITES.put(callee, true);
        }
        return writes;
    }

    /**
     * A method by name, any overload: one annotated as a write or listed in
     * {@link #WRITE_PRIMITIVES} writes; otherwise its bodies say. Only the
     * concrete top-level classes of the backend are read — anything else is
     * taken as writing nothing and calling nothing.
     */
    private static Edges edges(Callee callee) throws IOException {
        Edges cached = EDGES.get(callee);
        if (cached != null) {
            return cached;
        }
        Class<?> classe = callee.classe();
        Edges edges;
        if (WRITE_PRIMITIVES.getOrDefault(classe, Set.of()).contains(callee.name())) {
            edges = new Edges(true, Set.of());
        } else if (classe.isInterface()
                || classe.isArray()
                || classe.isPrimitive()
                || classe.getEnclosingClass() != null
                || Modifier.isAbstract(classe.getModifiers())
                || !classe.getName().startsWith(ROOT_PACKAGE + ".")) {
            edges = new Edges(false, Set.of());
        } else if (Arrays.stream(classe.getDeclaredMethods())
                .anyMatch(method -> method.getName().equals(callee.name())
                        && (method.isAnnotationPresent(RefusedWhileSolving.class)
                                || method.isAnnotationPresent(RefusedWhileFrozen.class)))) {
            edges = new Edges(true, Set.of());
        } else {
            edges = edges(
                    classe,
                    declarations(withoutComments(source(classe.getSimpleName())), callee.name()).stream()
                            .map(Declaration::body)
                            .toList());
        }
        EDGES.put(callee, edges);
        return edges;
    }

    /** What {@code bodies}, declared in {@code classe}, write by themselves, and what they call. */
    private static Edges edges(Class<?> classe, List<String> bodies) throws IOException {
        ClassFacts facts = facts(classe);
        boolean writes = false;
        Set<Callee> callees = new LinkedHashSet<>();
        for (String body : bodies) {
            writes |= WRITE_MARKER.matcher(body).find()
                    || facts.writingConstants() != null
                            && facts.writingConstants().matcher(body).find();
            Matcher own = CALL_ON_THIS.matcher(body);
            while (own.find()) {
                String name = own.group(own.group(1) != null ? 1 : 2);
                if (facts.methods().contains(name)) {
                    callees.add(new Callee(classe, name));
                }
            }
            Matcher onField = CALL_ON_FIELD.matcher(body);
            while (onField.find()) {
                Class<?> type = facts.fields().get(onField.group(1));
                if (type != null) {
                    callees.add(new Callee(type, onField.group(2)));
                }
            }
        }
        return new Edges(writes, callees);
    }

    /** Every call on {@code this}, as {@link #callsOnThis} sees one: the called name. */
    private static final Pattern CALL_ON_THIS =
            Pattern.compile("(?<![\\w.#:])(?:this\\.)?(\\w+)\\s*\\(|\\bthis\\s*::\\s*(\\w+)\\b");

    /**
     * A call on a field of this instance, unqualified or through
     * {@code this.} — not the same field read on another receiver:
     * {@code ReferentielCsvImportService}'s analysis collects closures
     * « service -> service.creneaux.importer(…) » that only {@code apply()}
     * runs, and a preview building them writes nothing.
     */
    private static final Pattern CALL_ON_FIELD =
            Pattern.compile("(?<![\\w.])(?:this\\.)?(\\w+)\\s*\\.\\s*(\\w+)\\s*\\(");

    /**
     * What {@link #edges} reads of a class once: the SQL constants that write,
     * the names of its methods, and the type of each of its fields.
     */
    private record ClassFacts(Pattern writingConstants, Set<String> methods, Map<String, Class<?>> fields) {}

    private static final Map<Class<?>, ClassFacts> FACTS = new HashMap<>();

    private static ClassFacts facts(Class<?> classe) throws IOException {
        ClassFacts cached = FACTS.get(classe);
        if (cached != null) {
            return cached;
        }
        List<String> constants = new ArrayList<>();
        Matcher constant = STRING_CONSTANT.matcher(withoutComments(source(classe.getSimpleName())));
        while (constant.find()) {
            if (WRITE_MARKER.matcher(constant.group(2)).find()) {
                constants.add(constant.group(1));
            }
        }
        Set<String> methods = Arrays.stream(classe.getDeclaredMethods())
                .filter(method -> !method.isSynthetic())
                .map(Method::getName)
                .collect(Collectors.toSet());
        Map<String, Class<?>> fields = new HashMap<>();
        for (Field field : classe.getDeclaredFields()) {
            fields.put(field.getName(), field.getType());
        }
        ClassFacts facts = new ClassFacts(
                constants.isEmpty() ? null : Pattern.compile("\\b(?:" + String.join("|", constants) + ")\\b"),
                methods,
                fields);
        FACTS.put(classe, facts);
        return facts;
    }

    /**
     * Whether {@code body} calls {@code method} on {@code this}: unqualified,
     * qualified by {@code this.}, or handed over as {@code this::method} — a
     * method reference goes through no proxy either.
     */
    private static boolean callsOnThis(String body, String method) {
        String name = Pattern.quote(method);
        return Pattern.compile("(?<![\\w.#:])(?:this\\.)?" + name + "\\s*\\(|\\bthis\\s*::\\s*" + name + "\\b")
                .matcher(body)
                .find();
    }

    private static String withoutComments(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
    }
}
