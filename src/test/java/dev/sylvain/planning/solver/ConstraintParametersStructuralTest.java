package dev.sylvain.planning.solver;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.domain.ParametresLegaux;
import dev.sylvain.planning.domain.ParametresQualite;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * A rule that reads a setting declares it, or the build fails.
 *
 * <p>{@link ConstraintParameters} is what the Contraintes screen shows beside
 * each rule: the value this edition stored, and the form that changes it. A
 * hand-written map like that rots the moment a constraint starts reading one
 * more parameter — and it rots <em>silently</em>, because the screen keeps
 * showing a plausible, incomplete list. This test is the net: it reads the
 * constraint sources, resolves what each rule reads <b>through its helpers</b>,
 * and fails on anything undeclared.
 *
 * <p>How the reading is resolved. The accessors of {@link ParametresLegaux}
 * and {@link ParametresQualite} are taken by reflection, never from a second
 * hand-written list — a getter added to either is in scope from the moment it
 * exists. The sources are then sliced method by method, and each constraint
 * method is walked transitively through the methods it calls: the weekly caps
 * read their break duration through {@code effectiveWorkMajeurMinutes}, three
 * frames below {@code asConstraint}, and a per-method scan would see nothing
 * there.
 *
 * <p>Two forms the walk cannot follow, and neither is an oversight:</p>
 * <ul>
 *   <li>a parameter reaching a rule as a <b>problem fact built elsewhere</b> —
 *       the meal windows are {@code FenetreRepas} instances assembled by
 *       {@code ReferenceDataService} from four legal parameters, so
 *       {@code RepasConstraints} names none of them. Those rules declare their
 *       settings and appear in {@link #LECTURES_INDIRECTES} with the reason;</li>
 *   <li>a value the rule compares against that is <b>not a setting at all</b> —
 *       the 10 h daily cap, the 11 h rest, the six days of weekly rest are
 *       constants of the Code du travail held in {@code PlafondsLegauxMajeurs}
 *       and {@code PlafondsLegauxMineurs}. Nothing on a form changes them, so
 *       there is nothing to link to, and the description is where they are
 *       named.</li>
 * </ul>
 */
class ConstraintParametersStructuralTest {

    private static final Path CONTRAINTES = Path.of("src/main/java/dev/sylvain/planning/solver/constraints");

    /** {@code .asConstraint("name")} — the id the catalogue and the screen key on. */
    private static final Pattern DECLARATION = Pattern.compile("asConstraint\\(\"([A-Za-z]+)\"\\)");

    /** A method declaration of the constraint classes, captured on its name. */
    private static final Pattern METHODE =
            Pattern.compile("(?m)^ {4}(?:public |private |protected )?(?:static )?(?:final )?[\\w.<>,?\\[\\]\\s]+?"
                    + "\\b(\\w+)\\s*\\(");

    /** Reading a parameter is always qualified — {@code parametres.getX()}. */
    private static final Pattern LECTURE = Pattern.compile("\\b(\\w+)\\s*\\(");

    /**
     * A call to a helper <b>of this class</b>, which the walk follows. The
     * lookbehind is what makes it a helper rather than a homonym: {@code of}
     * and {@code debut} are method names here and method names on half the JDK
     * besides, so following {@code LocalDateTime.of(…)} linked a rule to every
     * parameter its file reads anywhere.
     */
    private static final Pattern APPEL = Pattern.compile("(?<![.\\w])(\\w+)\\s*\\(");

    /**
     * Rules whose settings never appear in their own source, because the value
     * reaches them as a problem fact assembled outside the solver. Each entry
     * carries why, and the list can only shrink: a rule that reads its
     * parameters directly has no business here.
     */
    private static final Map<String, String> LECTURES_INDIRECTES = Map.of(
            "coupureRepasObligatoire",
                    "les fenêtres voyagent en FenetreRepas, assemblées par ReferenceDataService.fenetresRepas() "
                            + "depuis les quatre paramètres de coupure ; la contrainte ne voit que le fait",
            "coupureRepasPlacementPrefere",
                    "même chose : FenetreRepas est un fait du problème, la contrainte n'ouvre jamais "
                            + "ParametresLegaux",
            "pauseSurPosteSansRelais",
                    "les deux durées de pause sont lues par PauseSurPoste.dues, dans domain/, à travers "
                            + "dureePauseMinutes(mineur) — un accesseur à argument, que le balayage ne "
                            + "compte pas, dans un dossier qu'il ne lit pas");

    /**
     * Accessors that compute rather than store. {@code penaliseFermeturePuisOuverture}
     * is true only when the three thresholds it reads are all set, so a rule
     * declaring those three declares it: there is no fourth field to fill, and
     * pointing at one would send the reader looking for something that is not
     * on the form.
     */
    private static final Map<String, Set<String>> DERIVES = Map.of(
            "penaliseFermeturePuisOuverture",
            Set.of("heureServiceTardif", "heureServiceMatinal", "reposSouhaiteApresServiceTardifMinutes"));

    @Test
    void everyConstraintDeclaresTheSettingsItReads() throws IOException {
        Map<String, Set<String>> lectures = readingsByConstraint();
        Map<String, List<String>> declarations = ConstraintParameters.declarations();

        Map<String, Set<String>> manquants = new TreeMap<>();
        lectures.forEach((contrainte, accesseurs) -> {
            Set<String> declares = Set.copyOf(declarations.getOrDefault(contrainte, List.of()));
            Set<String> absents = new TreeSet<>();
            for (String accesseur : accesseurs) {
                if (!couvert(declares, accesseur)) {
                    absents.add(accesseur);
                }
            }
            if (!absents.isEmpty()) {
                manquants.put(contrainte, absents);
            }
        });

        assertThat(manquants).describedAs("""
                        Ces règles lisent un paramètre qu'elles ne déclarent pas. L'écran Contraintes \
                        affiche la valeur et le lien vers le formulaire à partir de ConstraintParameters : \
                        une lecture non déclarée est une valeur que l'organisateur ne verra jamais. \
                        Ajoutez la clé dans PAR_CONTRAINTE (et la référence dans REFERENCES si elle \
                        n'existe pas encore).""").isEmpty();
    }

    @Test
    void everyDeclaredSettingIsOneTheRuleActuallyReads() throws IOException {
        Map<String, Set<String>> lectures = readingsByConstraint();

        Map<String, Set<String>> injustifies = new TreeMap<>();
        ConstraintParameters.declarations().forEach((contrainte, cles) -> {
            if (LECTURES_INDIRECTES.containsKey(contrainte)) {
                return;
            }
            Set<String> accesseurs = lectures.getOrDefault(contrainte, Set.of());
            Set<String> orphelines = new TreeSet<>();
            for (String cle : cles) {
                if (accesseurs.stream().noneMatch(accesseur -> couvre(cle, accesseur))) {
                    orphelines.add(cle);
                }
            }
            if (!orphelines.isEmpty()) {
                injustifies.put(contrainte, orphelines);
            }
        });

        assertThat(injustifies).describedAs("""
                        Ces règles déclarent un paramètre qu'elles ne lisent pas. Soit la déclaration est \
                        périmée — retirez-la, l'écran annonce sinon un réglage sans effet —, soit la \
                        lecture passe par un fait du problème et la règle a sa place dans \
                        LECTURES_INDIRECTES, avec sa raison écrite.""").isEmpty();
    }

    @Test
    void everyDeclaredKeyIsOneTheReferencesCanValue() {
        Set<String> connues = ConstraintParameters.keys();
        Set<String> inconnues = ConstraintParameters.declarations().values().stream()
                .flatMap(List::stream)
                .filter(cle -> !connues.contains(cle))
                .collect(TreeSet::new, Set::add, Set::addAll);

        assertThat(inconnues)
                .describedAs("Une clé déclarée sans référence n'a ni libellé, ni valeur, ni écran : "
                        + "la ligne serait vide à l'écran.")
                .isEmpty();
    }

    @Test
    void everyReferenceIsDeclaredBySomeRule() {
        Set<String> declarees = ConstraintParameters.declarations().values().stream()
                .flatMap(List::stream)
                .collect(TreeSet::new, Set::add, Set::addAll);

        Set<String> orphelines = new TreeSet<>(ConstraintParameters.keys());
        orphelines.removeAll(declarees);

        assertThat(orphelines)
                .describedAs("Une référence que personne ne déclare ne s'affiche nulle part : "
                        + "retirez-la plutôt que de la laisser suggérer une couverture qui n'existe pas.")
                .isEmpty();
    }

    /**
     * True when {@code cle} names {@code accesseur}: the record component
     * itself, its {@code get}/{@code is} form, or a prefix of it — the one
     * concession, which lets a single key stand for a pair of bounds
     * ({@code coupureRepasMidi} for {@code getCoupureRepasMidiDebut} and
     * {@code …Fin}).
     */
    private static boolean couvert(Set<String> declares, String accesseur) {
        Set<String> sources = DERIVES.get(accesseur);
        if (sources != null) {
            return declares.containsAll(sources);
        }
        return declares.stream().anyMatch(cle -> couvre(cle, accesseur));
    }

    private static boolean couvre(String cle, String accesseur) {
        String capitalise = Character.toUpperCase(cle.charAt(0)) + cle.substring(1);
        return accesseur.equals(cle)
                || accesseur.equals("get" + capitalise)
                || accesseur.equals("is" + capitalise)
                || accesseur.startsWith("get" + capitalise)
                || accesseur.startsWith("is" + capitalise);
    }

    /** Every parameter accessor each constraint reaches, helpers included. */
    private static Map<String, Set<String>> readingsByConstraint() throws IOException {
        Set<String> accesseurs = parameterAccessors();
        Map<String, Set<String>> parContrainte = new TreeMap<>();

        for (Path fichier : fichiers()) {
            String source = Files.readString(fichier);
            Map<String, String> corps = bodiesByMethod(source);

            Map<String, Set<String>> lus = new HashMap<>();
            Map<String, Set<String>> appelles = new HashMap<>();
            corps.forEach((methode, corpsMethode) -> {
                Set<String> lectures = new HashSet<>();
                Set<String> appels = new HashSet<>();
                Matcher lecture = LECTURE.matcher(corpsMethode);
                while (lecture.find()) {
                    if (accesseurs.contains(lecture.group(1))) {
                        lectures.add(lecture.group(1));
                    }
                }
                Matcher appel = APPEL.matcher(corpsMethode);
                while (appel.find()) {
                    if (!accesseurs.contains(appel.group(1)) && corps.containsKey(appel.group(1))) {
                        appels.add(appel.group(1));
                    }
                }
                // A method reference — LegalConstraints::getDureePauseMajeurMinutes
                // and the like — carries no parentheses, so the call pattern
                // above never sees it.
                for (String accesseur : accesseurs) {
                    if (corpsMethode.contains("::" + accesseur)) {
                        lectures.add(accesseur);
                    }
                }
                for (String methodeCitee : corps.keySet()) {
                    if (corpsMethode.contains("::" + methodeCitee)) {
                        appels.add(methodeCitee);
                    }
                }
                lus.put(methode, lectures);
                appelles.put(methode, appels);
            });

            corps.forEach((methode, corpsMethode) -> {
                Matcher matcher = DECLARATION.matcher(corpsMethode);
                while (matcher.find()) {
                    parContrainte
                            .computeIfAbsent(matcher.group(1), nom -> new TreeSet<>())
                            .addAll(fermeture(methode, lus, appelles));
                }
            });
        }
        return parContrainte;
    }

    /** Accessors reached from {@code depart}, following the calls it makes. */
    private static Set<String> fermeture(
            String depart, Map<String, Set<String>> lus, Map<String, Set<String>> appelles) {
        Set<String> lectures = new TreeSet<>();
        Set<String> vus = new HashSet<>();
        Deque<String> aVisiter = new ArrayDeque<>();
        aVisiter.add(depart);
        while (!aVisiter.isEmpty()) {
            String methode = aVisiter.poll();
            if (!vus.add(methode)) {
                continue;
            }
            lectures.addAll(lus.getOrDefault(methode, Set.of()));
            aVisiter.addAll(appelles.getOrDefault(methode, Set.of()));
        }
        return lectures;
    }

    /**
     * Method bodies of one source file, keyed on the method name, sliced by
     * counting braces from the declaration's own opening one.
     */
    private static Map<String, String> bodiesByMethod(String source) {
        Map<String, String> corps = new HashMap<>();
        Matcher matcher = METHODE.matcher(source);
        while (matcher.find()) {
            // A field initialised from a call reads exactly like a method
            // declaration up to its opening parenthesis; the assignment is what
            // tells them apart, and slicing from one swallows the rest of the
            // class into a body nobody wrote.
            int debutLigne = source.lastIndexOf('\n', matcher.start()) + 1;
            if (source.substring(debutLigne, matcher.end()).indexOf('=') >= 0) {
                continue;
            }
            int parenthese = endOfParameterList(source, matcher.end() - 1);
            if (parenthese < 0) {
                continue;
            }
            int accolade = source.indexOf('{', parenthese);
            if (accolade < 0 || source.substring(parenthese + 1, accolade).contains(";")) {
                continue;
            }
            int profondeur = 0;
            int fin = accolade;
            for (int index = accolade; index < source.length(); index++) {
                char caractere = source.charAt(index);
                if (caractere == '{') {
                    profondeur++;
                } else if (caractere == '}') {
                    profondeur--;
                    if (profondeur == 0) {
                        fin = index;
                        break;
                    }
                }
            }
            corps.merge(matcher.group(1), source.substring(accolade, fin + 1), (premier, second) -> premier + second);
        }
        return corps;
    }

    /** Index of the parenthesis closing the parameter list opened at {@code ouvrante}. */
    private static int endOfParameterList(String source, int ouvrante) {
        int profondeur = 0;
        for (int index = ouvrante; index < source.length(); index++) {
            char caractere = source.charAt(index);
            if (caractere == '(') {
                profondeur++;
            } else if (caractere == ')') {
                profondeur--;
                if (profondeur == 0) {
                    return index;
                }
            }
        }
        return -1;
    }

    /** Reflected, never listed by hand: a new getter is in scope the day it lands. */
    private static Set<String> parameterAccessors() {
        Set<String> noms = new TreeSet<>();
        for (Class<?> type : List.of(ParametresLegaux.class, ParametresQualite.class)) {
            for (Method methode : type.getDeclaredMethods()) {
                if (methode.getParameterCount() == 0 && !methode.getName().startsWith("set")) {
                    noms.add(methode.getName());
                }
            }
        }
        noms.removeAll(Set.of("toString", "hashCode", "equals", "clone"));
        return noms;
    }

    private static List<Path> fichiers() throws IOException {
        try (Stream<Path> chemins = Files.walk(CONTRAINTES)) {
            List<Path> fichiers =
                    new ArrayList<>(chemins.filter(chemin -> chemin.toString().endsWith(".java"))
                            .toList());
            fichiers.sort(Path::compareTo);
            return fichiers;
        }
    }
}
