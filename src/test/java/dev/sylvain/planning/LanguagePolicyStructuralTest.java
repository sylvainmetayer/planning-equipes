package dev.sylvain.planning;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * The repository writes its prose in English and its business names in French,
 * and that rule has been in {@code AGENTS.md} for a long time. It did not hold:
 * a single branch added twenty French javadoc blocks — a third of all the
 * French prose in the backend — without anything saying a word. A convention
 * nothing checks drifts, exactly like the 56 orphan i18n ids that piled up
 * before {@code check-i18n.js} existed.
 *
 * <p>So this is a test, not another paragraph. It reads the backend sources and
 * fails on two things:</p>
 *
 * <ul>
 *   <li>a comment block written in French — the prose explains <em>why</em>, and
 *       the person reading it may not read French;</li>
 *   <li>a declared name (type, method) built on a French word that is not in
 *       the business glossary — {@code buildFromReferenceData} rather
 *       than {@code buildFromReferenceData}.</li>
 * </ul>
 *
 * <p>The glossary is the white list, and it lives in {@code AGENTS.md}:
 * {@code Animateur}, {@code Stand}, {@code Creneau}, {@code PosteAffectation},
 * {@code Vacation}… are the words of the staffing workbook, they carry a regulatory
 * meaning, and translating them would lose it. Everything else — verbs above
 * all — is English.</p>
 *
 * <p>Its first run, on the tree that preceded the remediation, failed on 315
 * French comment blocks and 522 French declared names. That failure is the
 * point of the test: the rule it checks had been written down for a long time
 * and nothing had ever read it back.</p>
 */
class LanguagePolicyStructuralTest {

    private static final List<Path> SOURCES = List.of(
            Path.of("src/main/java/dev/sylvain/planning"),
            Path.of("src/test/java/dev/sylvain/planning"));

    /**
     * The business glossary, mirrored from {@code AGENTS.md}. A French word
     * listed here may be used in an identifier because it <em>is</em> the
     * business term: it names a row of the staffing workbook, a legal notion, or a
     * key already frozen on the wire (a JSON property, an SQL column, a YAML
     * section) that no rename can move alone.
     */
    private static final Set<String> GLOSSAIRE = mots("""
            animateur animateurs stand stands creneau creneaux poste postes
            affectation affectations emplacement emplacements verrouillage verrouillages
            demande demandes echange echanges typologie typologies horaire horaires
            decoupage vacation vacations amplitude amplitudes edition editions
            contrainte contraintes planning plannings evenement evenements festival espace espaces
            indisponibilite indisponibilites indisponible indisponibles ouverture ouvertures
            competence competences souhait souhaits parametre parametres legal legaux
            mineur mineurs majeur majeurs effectif effectifs repos scenario scenarios
            solveur foire ninja premium qualite niveau referent
            jour jours journee semaine semaines heure heures duree fenetre fenetres
            repas pause midi soir nuit matin ferie hebdomadaire quotidien quotidienne
            nom prenom naissance motif raison statut libelle commentaire acces
            resolution violation violations segment segments""");

    /**
     * The French words this test refuses inside a declared name. It is the
     * lexicon of the franglais audit, minus the glossary above: verbs first
     * ("construire", "verifier", "lire"), then the common nouns and grammar
     * words that carry no business meaning ("cible", "jeton", "statement",
     * "par", "de").
     *
     * <p>Words that are also English keep out of it on purpose — "sections",
     * "anomalies", "limiter", "modifier", "importer", "minimal" and the
     * article "a" would flag correct English names. The lexicon is hand
     * written, so it under-detects rather than crying wolf: it is a floor, not
     * a proof.</p>
     */
    private static final Set<String> LEXIQUE_FR = mots("""
            construire construit construis executer valider valide valides validee
            verifier verifie lire ecrire ecrit supprimer supprime supprimes
            lister creer cree charger appliquer calculer trouver envoyer generer
            simuler exiger decouper compacter copier masquer eviter expliquer
            accepter refuser decliner demander ouvrir enregistrer
            regenerer fusionner normaliser decrire diagnostiquer compter equilibrer
            capturer purger enchainer dupliquer definir configurer reveler
            accorder soumettre annuler memoriser analyser ajouter deplacer
            marquer persister persiste attribuer verrouiller deverrouiller parcourir
            remplir chercher afficher selectionner filtrer trier convertir associer
            detecter estimer rejeter resoudre annoncer previsualiser terminer
            demarrer arreter effacer vider liberer
            est sont soit
            adresse anomalie blocage chevauchement cible cle cles collegue
            connexion couverture donnees dure dures ecart ecran famille familles
            fichier jeton lot paire perimetre plafond ponderation ponderations
            proprietaire requete requis requise strategie tache taches texte
            travail travailles utilisateur visite volumetrie vue vues
            absents automatique chiffree concerne concernes concernant continu
            derniere desactive desactivees dirige effectives egales fige figee
            forcee incompatibles interdit maximale minimale moins nombre
            ouverte fermee premiere proposees recue recues rejouable resolus
            au aux avant apres avec dans de des du en entre et la le les
            ou par plus pour sans si tous toutes toute un une vers""");

    /**
     * French function words against English ones — the majority side names the
     * language of a comment block. Short blocks that contain neither stay
     * unclassified rather than being guessed at.
     */
    private static final Set<String> OUTILS_FR = mots("""
            le la les des une un du de dans qui que pour sur est sont pas ne se cette
            ces aux par avec mais donc ou il elle nous leur son sa ses au en
            qu lorsque quand chaque tout toute tous toutes ainsi car
            alors comme sans deja meme etre fait faut peut doit ils elles vers depuis
            entre sous celui celle ceux""");

    private static final Set<String> OUTILS_EN = mots("""
            the of and to is that it in for with as this are be not but which from on
            by an its when each all any then so was were has have had they he she we
            you if while such does do can may must should would could there their our
            where what how at into onto only no same both than these those who why
            will over under after before without against between about because since
            still yet here once every another rather instead""");

    /**
     * The names this test lets through, and why. Keep this list short: an entry
     * is a claim that English would say it worse, not a place to park work.
     *
     * <ul>
     *   <li>{@code EditionCibleDto} — the name of a scenario DTO is published:
     *       {@code docs/schema/scenario-schema.json} keys its {@code $defs} on
     *       the class name, and that schema is what validates the YAML files
     *       users keep outside the repository. Renaming the class rewrites the
     *       schema, so this one waits for the day the DTO names are decoupled
     *       from it.</li>
     *   <li>{@code PlanningServiceScenarioContinuTest} and its
     *       {@code assertScenarioContinuSplitWithoutHard} — "Continu" here is
     *       not an adjective, it is the name of the fixture the test solves
     *       ({@code src/main/resources/scenarios/scenario-continu.yaml}).
     *       Translating the class would break the pairing with the file, the
     *       way renaming a constraint would break the pairing with
     *       {@code constraint_toggle}.</li>
     * </ul>
     *
     * <p>The constraint methods of {@code solver/constraints} are exempt too,
     * but by a rule rather than by a list: see
     * {@link #constraintNames(String)}.</p>
     *
     * <p>MCP tool names are out of scope by construction: they are declared
     * package-private, so the method pattern never sees them. Renaming them is
     * a lot of its own — the name of the tool <em>is</em> the name of the
     * method, and what a French speaking assistant picks depends on it.</p>
     */
    private static final Set<String> EXCEPTIONS_ASSUMEES = Set.of(
            "EditionCibleDto",
            "PlanningServiceScenarioContinuTest",
            "assertScenarioContinuSplitWithoutHard");

    private static final Pattern BLOC = Pattern.compile("/\\*\\*.*?\\*/|/\\*(?!\\*).*?\\*/", Pattern.DOTALL);
    private static final Pattern LIGNE = Pattern.compile("^\\s*//(.*)$");
    private static final Pattern MOT = Pattern.compile("[\\p{L}']+");
    private static final Pattern ACCENT = Pattern.compile("[àâäéèêëîïôöùûüÿç]");
    private static final Pattern TOKEN = Pattern.compile("[A-Z]?[a-z]+|[A-Z]+(?![a-z])|\\d+");
    private static final Pattern TYPE =
            Pattern.compile("\\b(?:class|interface|enum|record|@interface)\\s+([A-Z]\\w*)");
    private static final Pattern METHODE = Pattern.compile(
            "^\\s*(?:@\\w+(?:\\([^)]*\\))?\\s+)*"
                    + "(?:(?:public|private|protected|static|final|abstract|synchronized|default|native|strictfp)\\s+)+"
                    + "(?:<[^>]{0,120}>\\s*)?[\\w$][\\w$<>,\\[\\].?\\s]*?\\s+([a-z][\\w$]*)\\s*\\(");
    private static final Pattern CONTRAINTE = Pattern.compile("asConstraint\\(\"([A-Za-z0-9_]+)\"\\)");
    private static final Set<String> MOTS_CLES = Set.of(
            "if", "for", "while", "switch", "catch", "try", "return", "new", "super", "this",
            "synchronized", "do", "else", "case", "record", "yield", "instanceof");

    private static Set<String> mots(String bloc) {
        return Set.copyOf(List.of(bloc.trim().split("\\s+")));
    }

    private static List<Path> files() throws IOException {
        List<Path> files = new ArrayList<>();
        for (Path racine : SOURCES) {
            try (Stream<Path> flux = Files.walk(racine)) {
                files.addAll(flux.filter(f -> f.toString().endsWith(".java")).sorted().toList());
            }
        }
        return files;
    }

    /** A block of prose: its first line, and its text stripped of javadoc syntax. */
    private record Bloc(int ligne, String text) {
    }

    private static List<Bloc> blocs(String source) {
        List<Bloc> blocs = new ArrayList<>();
        Matcher commentaire = BLOC.matcher(source);
        while (commentaire.find()) {
            blocs.add(new Bloc(ligne(source, commentaire.start()), prose(commentaire.group())));
        }
        String[] lignes = source.split("\n", -1);
        boolean inProgress = false;
        StringBuilder suite = new StringBuilder();
        int debut = 0;
        for (int i = 0; i < lignes.length; i++) {
            Matcher ligne = LIGNE.matcher(lignes[i]);
            if (ligne.matches()) {
                if (!inProgress) {
                    debut = i + 1;
                    suite.setLength(0);
                }
                suite.append(ligne.group(1)).append(' ');
                inProgress = true;
            } else if (inProgress) {
                blocs.add(new Bloc(debut, prose(suite.toString())));
                inProgress = false;
            }
        }
        if (inProgress) {
            blocs.add(new Bloc(debut, prose(suite.toString())));
        }
        return blocs;
    }

    /**
     * Javadoc markup carries no language: {@code {@link Foo}} is neither French
     * nor English. Neither does a quotation in guillemets: the constraints of
     * {@code LegalConstraints} cite the Code du travail verbatim, and
     * translating an article of French law would be worse than useless.
     */
    private static String prose(String raw) {
        return raw.replaceAll("(?m)^\\s*\\*", " ")
                .replaceAll("\\{@\\w+\\s+[^}]*}", " ")
                .replaceAll("@\\w+", " ")
                .replaceAll("<[^>]*>", " ")
                .replaceAll("«[^»]*»", " ");
    }

    /**
     * French function words against English ones, plus one more piece of
     * evidence: a word carrying a French accent. English prose does not write
     * "indisponibilité", and the glossary asks for "timeslot" rather than
     * "créneau", so an accent inside a comment is a French word almost every
     * time.
     *
     * <p>It stays a heuristic and it under-detects: a block made of business
     * nouns with neither function word nor accent — "repos quotidien minimal" —
     * reads as no language at all and passes. This test is a floor, not a
     * proof.</p>
     */
    private static boolean isFrench(String text) {
        int fr = 0;
        int en = 0;
        Matcher mot = MOT.matcher(text.toLowerCase(Locale.ROOT));
        while (mot.find()) {
            String candidat = mot.group().replace("'", "");
            if (OUTILS_FR.contains(candidat)) {
                fr++;
            }
            if (OUTILS_EN.contains(candidat)) {
                en++;
            }
            if (ACCENT.matcher(candidat).find()) {
                fr++;
            }
        }
        return fr > en;
    }

    private static int ligne(String source, int position) {
        return (int) source.substring(0, position).chars().filter(c -> c == '\n').count() + 1;
    }

    /** A declared name and where it was read. */
    private record Nom(String file, String valeur) {
    }

    private static List<Nom> nomsDeclares(Path file, String source) {
        List<Nom> noms = new ArrayList<>();
        String fileName = file.getFileName().toString();
        Matcher type = TYPE.matcher(withoutLiterals(source));
        while (type.find()) {
            noms.add(new Nom(fileName, type.group(1)));
        }
        for (String ligne : source.split("\n")) {
            Matcher methode = METHODE.matcher(ligne);
            if (methode.find() && !MOTS_CLES.contains(methode.group(1)) && !isAccessor(methode.group(1))) {
                noms.add(new Nom(fileName, methode.group(1)));
            }
        }
        return noms;
    }

    /**
     * A bean accessor is not a method name, it is a JSON key: {@code
     * Animateur.getNom()} is the {@code nom} property the frontend and every
     * MCP client read. Renaming it would rename the contract, which the audit
     * of the franglais keeps out of scope until a {@code @JsonProperty} layer
     * exists. Same reason record components are not scanned: they have no
     * modifier, so the method pattern never sees them.
     */
    private static boolean isAccessor(String nom) {
        return nom.matches("^(get|set|is)[A-Z].*");
    }

    /**
     * The constraint methods of {@code solver/constraints} carry the name of
     * the constraint they build, and that name is a primary key in
     * {@code constraint_toggle} as well as the id of the score explanation:
     * renaming the method without renaming the literal would break the pairing
     * a reader relies on, and renaming both would silently re-enable a
     * constraint somebody turned off. The exemption is therefore verified
     * rather than declared — a method only escapes if the very same file
     * registers a constraint under that name.
     */
    private static Set<String> constraintNames(String source) {
        Set<String> noms = new LinkedHashSet<>();
        Matcher contrainte = CONTRAINTE.matcher(source);
        while (contrainte.find()) {
            noms.add(contrainte.group(1));
        }
        return noms;
    }

    private static String withoutLiterals(String source) {
        return source.replaceAll("\"\"\"(?s).*?\"\"\"", "\"\"")
                .replaceAll("\"(?:[^\"\\\\\n]|\\\\.)*\"", "\"\"");
    }

    private static List<String> motsFrancais(String nom) {
        List<String> fautifs = new ArrayList<>();
        Matcher token = TOKEN.matcher(nom);
        while (token.find()) {
            String mot = token.group().toLowerCase(Locale.ROOT);
            if (LEXIQUE_FR.contains(mot) && !GLOSSAIRE.contains(mot)) {
                fautifs.add(mot);
            }
        }
        return fautifs;
    }

    @Test
    void commentBlocksAreWrittenInEnglish() throws IOException {
        List<String> francais = new ArrayList<>();
        for (Path file : files()) {
            String source = Files.readString(file);
            for (Bloc bloc : blocs(source)) {
                if (isFrench(bloc.text())) {
                    francais.add(file.getFileName() + ":" + bloc.ligne());
                }
            }
        }

        assertThat(francais)
                .as("comment blocks written in French — the prose of this repository is English, "
                        + "only the business vocabulary of the glossary stays French")
                .isEmpty();
    }

    @Test
    void declaredNamesUseFrenchOnlyForBusinessVocabulary() throws IOException {
        List<String> fautifs = new ArrayList<>();
        for (Path file : files()) {
            String source = Files.readString(file);
            Set<String> contraintes = constraintNames(source);
            for (Nom nom : nomsDeclares(file, source)) {
                if (contraintes.contains(nom.valeur()) || EXCEPTIONS_ASSUMEES.contains(nom.valeur())) {
                    continue;
                }
                List<String> mots = motsFrancais(nom.valeur());
                if (!mots.isEmpty()) {
                    fautifs.add(nom.file() + " : " + nom.valeur() + " " + mots);
                }
            }
        }

        assertThat(fautifs)
                .as("declared names built on a French word outside the glossary — verbs and common "
                        + "nouns are English, only the business vocabulary stays French")
                .isEmpty();
    }

    /**
     * Both tests above are worth what their scan is worth: a pattern that stops
     * matching would turn them green for the worst possible reason. This one
     * states what the scan is expected to see at all.
     */
    @Test
    void theScanReallyReadsTheBackendSources() throws IOException {
        int files = 0;
        int blocs = 0;
        int noms = 0;
        int contraintes = 0;
        for (Path file : files()) {
            String source = Files.readString(file);
            files++;
            blocs += blocs(source).size();
            noms += nomsDeclares(file, source).size();
            contraintes += constraintNames(source).size();
        }

        assertThat(files).as("java files scanned").isGreaterThan(300);
        assertThat(blocs).as("comment blocks read").isGreaterThan(1500);
        assertThat(noms).as("declared types and methods read").isGreaterThan(1200);
        assertThat(contraintes).as("constraint names read, which drive the exemption")
                .isGreaterThan(35);
    }

    /** An exemption that no longer matches anything must leave the list. */
    @Test
    void everyAssumedExceptionStillNamesRealCode() throws IOException {
        Set<String> declares = new LinkedHashSet<>();
        for (Path file : files()) {
            for (Nom nom : nomsDeclares(file, Files.readString(file))) {
                declares.add(nom.valeur());
            }
        }

        assertThat(declares).containsAll(EXCEPTIONS_ASSUMEES);
    }
}
