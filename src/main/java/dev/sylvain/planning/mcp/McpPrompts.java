package dev.sylvain.planning.mcp;

import java.lang.reflect.Method;
import java.util.List;

import io.quarkiverse.mcp.server.Prompt;
import io.quarkiverse.mcp.server.PromptArg;
import io.quarkiverse.mcp.server.PromptMessage;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * The conversations this server is actually for, served as MCP prompts
 * instead of being copy-pasted: one per moment of a real event, from the
 * empty grid to the swap requests that arrive once everybody has read their
 * planning.
 *
 * <p>The MCP page hands the user a ready-to-copy prompt, and that text had
 * already drifted: it named a tool the application has never exposed. A prompt
 * the server itself announces cannot drift that way — it lives next to the
 * tools it names, and {@code McpToolNamesTest} reads this file.</p>
 *
 * <p>These are deliberately <b>not</b> the localized string of the page. That
 * one is displayed, translated and copied by a human; this one is executed.
 * Keeping the page's copy is what serves a client with no prompt support.</p>
 *
 * <p>No {@code @EditionCiblee} here: a prompt writes nothing and reads
 * nothing. It weaves the edition into the text it hands back, and the tools
 * the assistant then calls carry it themselves.</p>
 *
 * <p>{@link #catalogue()} serves the same texts to the MCP page, for a client
 * that does not support prompts. The page used to carry its own copies, and
 * one of them had drifted to a tool this application never exposed — reading
 * them from here is what makes that impossible rather than merely
 * unlikely.</p>
 */
@ApplicationScoped
public class McpPrompts {

    private static final String EDITION = "Id ou nom de l'édition à traiter ; omis, l'édition par défaut";

    /**
     * Display order on the MCP page, which is the order of a real event:
     * build the grid, check it, solve, then diagnose what is left.
     *
     * <p>Declared rather than derived from {@code getDeclaredMethods()}, whose
     * order the JVM does not guarantee — a page whose sections reshuffle
     * between two deployments reads as a bug. {@code McpPromptsResourcesTest}
     * fails if this list and the annotated methods ever diverge.</p>
     */
    private static final List<String> ORDRE = List.of(
            "construire_la_grille_de_creneaux",
            "traiter_les_declarations_de_disponibilite",
            "verifier_avant_resolution",
            "resoudre_sans_perdre_le_planning",
            "diagnostiquer_contraintes_dures",
            "publier_le_planning",
            "traiter_les_demandes_dechange");

    @Prompt(description = "Diagnostiquer les contraintes dures encore violées après une résolution, et dire "
            + "quoi corriger dans les données de référence.")
    PromptMessage diagnostiquer_contraintes_dures(
            @PromptArg(description = EDITION, required = false) String edition) {
        return PromptMessage.withUserRole("""
                Le dernier planning résolu%s contient des violations de contraintes dures.

                1. Appelle etat_planning pour savoir si le planning affiché est encore à jour.
                2. Appelle diagnostiquer_plan pour recalculer le score du plan persisté, puis \
                expliquer_echec_contraintes_dures pour le détail des violations.
                3. Pour chaque contrainte HARD en défaut, identifie les postes touchés avec \
                lister_affectations puis expliquer_affectation, et donne la cause racine probable \
                (compétences manquantes sur la typologie, indisponibilité, effectif insuffisant sur la \
                tranche, plafond légal atteint, aucun animateur polyvalent…).
                4. Recoupe avec analyser_faisabilite et analyser_effectifs : si le problème est \
                structurellement infaisable, aucune résolution ne le corrigera.
                5. Termine par des actions concrètes classées par impact décroissant, en disant pour \
                chacune quel outil l'appliquerait.

                N'expose aucune donnée nominative : les animateurs se désignent par leur id.""".formatted(suffixe(edition)));
    }

    @Prompt(description = "Vérifier qu'une édition est prête à être résolue : grille de créneaux, ouvertures "
            + "de stands, effectifs, avant de lancer quoi que ce soit.")
    PromptMessage verifier_avant_resolution(
            @PromptArg(description = EDITION, required = false) String edition) {
        return PromptMessage.withUserRole("""
                Vérifie que %s est prête à être résolue, sans rien lancer ni rien modifier.

                1. volumes : y a-t-il des animateurs et des postes à pourvoir ?
                2. lister_declarations_disponibilite avec statut EN_ATTENTE : une déclaration non \
                décidée n'est pas dans le référentiel, et résoudre avant de la traiter, c'est résoudre \
                le mauvais problème. Dis-moi combien il en reste.
                3. valider_creneaux : la grille est-elle cohérente ? Le mode est obligatoire — si tu \
                hésites entre AMPLITUDES et VACATIONS, appelle diagnostiquer_grille_creneaux et \
                demande-moi.
                4. analyser_ouvertures_stands : y a-t-il des stands jamais ouverts, des fenêtres sans \
                effet, des segments trop courts ?
                5. analyser_effectifs : combien d'animateurs faut-il au minimum, et l'effectif présent \
                suffit-il ?
                6. analyser_faisabilite : reste-t-il une cause structurellement bloquante ?
                7. consulter_parametres_legaux : la pause minimale entre vacations et la pause sur le \
                poste sont-elles réglées comme l'organisateur le veut ? À 30 minutes entre vacations, \
                deux blocs qui se touchent exigent deux équipes ; sans la pause sur le poste, aucune \
                séquence ne peut dépasser six heures.

                Conclus par oui/non, puis par la liste de ce qui reste à corriger avant de lancer une \
                résolution.""".formatted(designation(edition)));
    }

    @Prompt(description = "Relancer une résolution sans risquer de perdre le planning en place : capturer, "
            + "résoudre, comparer, restaurer si c'est pire.")
    PromptMessage resoudre_sans_perdre_le_planning(
            @PromptArg(description = EDITION, required = false) String edition) {
        return PromptMessage.withUserRole("""
                Relance une résolution%s en gardant la possibilité de revenir en arrière.

                1. capturer_instantane avec un libellé qui dit d'où on part.
                2. Si une partie du planning est déjà bonne, fige-la avec verrouiller avant de \
                relancer — sinon la résolution la défera.
                3. lancer_solveur, puis statut_solveur jusqu'à la fin du job.
                4. comparer_instantanes entre l'instantané capturé et « courant ».
                5. Dis-moi ce qui a changé : score, postes pourvus, violations par contrainte. Si le \
                résultat est moins bon, propose restaurer_instantane — mais ne le fais pas sans mon \
                accord. Si la pause sur le poste est déclarée (consulter_parametres_legaux), appelle \
                analyser_pauses avec sansRelaisSeulement : chaque pause sans relais est un relais à \
                organiser avant de publier.

                6. Rappelle-moi que résoudre n'est pas prévenir : tant que le planning n'est pas \
                publié, les animateurs lisent toujours le précédent. etat_publication dit depuis quand.

                Ne relance pas une deuxième résolution de ta propre initiative.""".formatted(suffixe(edition)));
    }

    @Prompt(description = "Poser une grille de créneaux récurrents sans les saisir un par un, et faire "
            + "contrôler la grille obtenue avant de la garder.")
    PromptMessage construire_la_grille_de_creneaux(
            @PromptArg(description = EDITION, required = false) String edition) {
        return PromptMessage.withUserRole("""
                Construis la grille de créneaux%s.

                1. diagnostiquer_grille_creneaux pour voir ce qui existe déjà.
                2. Demande-moi si la grille doit être en AMPLITUDES (journées à découper en vacations) \
                ou en VACATIONS (vacations finales) : le verdict de la validation en dépend, ne le devine \
                pas.
                3. Si les stands ont déjà leurs horaires (lister_stands), propose-moi d'abord \
                previsualiser_derivation_creneaux : la grille découle alors de leurs fenêtres sans être \
                saisie une seconde fois. Sinon, previsualiser_creneaux_recurrents pour me montrer ce que \
                ta règle produirait — une règle qui se trompe d'une heure crée des dizaines de lignes d'un \
                coup.
                4. generer_creneaux_depuis_stands ou creer_creneaux_recurrents seulement après mon accord \
                explicite.
                5. valider_creneaux pour finir, et explique-moi chaque anomalie — doublon, chevauchement, \
                trou dans une journée, date isolée, stand que personne ne pourra armer, sous-effectif — en \
                disant pour chacune si c'est une vraie erreur ou un choix légitime de ma part.""".formatted(suffixe(edition)));
    }

    @Prompt(description = "Traiter les déclarations de disponibilité envoyées par les animateurs : les "
            + "lire, les appliquer ou les refuser, avant de résoudre.")
    PromptMessage traiter_les_declarations_de_disponibilite(
            @PromptArg(description = EDITION, required = false) String edition) {
        return PromptMessage.withUserRole("""
                Aide-moi à traiter les déclarations de disponibilité en attente%s.

                1. consulter_collecte_disponibilites : la collecte est-elle encore ouverte ? Une \
                déclaration peut encore arriver après celles que nous allons lire.
                2. lister_declarations_disponibilite avec statut EN_ATTENTE. Pour chacune, compare ce \
                qui est déclaré (joursIndisponibles, souhaits) avec ce que la fiche dit aujourd'hui \
                (joursActuels) : dis-moi ce qui changerait vraiment.
                3. Signale-moi celles qui coûtent cher avant de les appliquer : un jour retiré sur une \
                journée déjà tendue se voit avec analyser_effectifs et analyser_faisabilite.
                4. appliquer_declaration_disponibilite ou refuser_declaration_disponibilite, une par \
                une et seulement après mon accord — appliquer écrit sur la fiche, tout ou rien, et il \
                n'y a pas de retour en arrière.
                5. Quand il n'en reste plus, rappelle-moi que le planning résolu est maintenant \
                périmé : etat_planning le dit, et il faut relancer une résolution.

                Les animateurs se désignent par leur id : ne me demande pas de noms, tu n'en verras \
                pas.""".formatted(suffixe(edition)));
    }

    @Prompt(description = "Publier le planning aux animateurs : vérifier qui est concerné et ce qu'ils "
            + "liront, publier, puis contrôler qui a bien été prévenu.")
    PromptMessage publier_le_planning(
            @PromptArg(description = EDITION, required = false) String edition) {
        return PromptMessage.withUserRole("""
                Prépare la publication du planning%s. **Publier envoie des courriels** : ne le fais \
                pas sans mon accord explicite.

                1. etat_publication : depuis quand rien n'est parti, combien de personnes sont \
                concernées, et ce que chacune lirait. Zéro concerné veut dire que le planning publié \
                est déjà à jour — il n'y a rien à faire.
                2. Si une résolution tourne, ou si le plan à publier est vide, la publication sera \
                refusée : dis-le-moi plutôt que de réessayer.
                3. diagnostiquer_plan avant d'envoyer : publier un planning qui viole encore des \
                contraintes dures, c'est faire lire à quelqu'un un horaire qu'on va lui reprendre.
                4. Signale-moi les personnes dont adresseConnue est faux : elles ne recevront rien et \
                devront être prévenues autrement.
                5. publier_planning seulement après mon accord.
                6. lister_destinataires_publication pour contrôler le résultat, et \
                envoyer_planning_animateur — un courriel de plus — pour rattraper un échec isolé, \
                toujours après mon accord.

                Les destinataires se désignent par leur id : ni nom ni adresse ne sortent d'ici.""".formatted(suffixe(edition)));
    }

    @Prompt(description = "Trancher les demandes d'échange des animateurs : chiffrer l'impact de chacune sur "
            + "le planning d'aujourd'hui, puis accepter ou refuser.")
    PromptMessage traiter_les_demandes_dechange(
            @PromptArg(description = EDITION, required = false) String edition) {
        return PromptMessage.withUserRole("""
                Aide-moi à trancher les demandes d'échange en attente%s.

                1. consulter_foire_echanges : la foire accepte-t-elle encore des demandes aujourd'hui ?
                2. lister_demandes_echange avec statut PROPOSEE — celles dont le collègue visé a déjà \
                donné son accord, et qui n'attendent que nous. Celles en EN_ATTENTE_CIBLE ne sont pas \
                à nous.
                3. Pour chacune, analyser_impact_echange : la prévalidation stockée décrit le planning \
                du jour où la demande a été envoyée, pas celui d'aujourd'hui. Classe-les par delta de \
                score, et mets à part celles qui cassent une contrainte dure.
                4. accepter_demande_echange ou refuser_demande_echange, une par une et après mon \
                accord. Accepter écrit dans le planning résolu et le fige par des verrouillages : une \
                résolution ultérieure ne le défera pas, mais lister_verrouillages s'allonge d'autant.
                5. Rappelle-moi de publier ensuite : un échange accepté n'est annoncé à personne tant \
                que la publication n'est pas partie.

                Demandeur et cible se désignent par leur id.""".formatted(suffixe(edition)));
    }

    /**
     * The prompts as the MCP page shows them: name, what each is for, and the
     * text itself, built for the default edition.
     *
     * <p>The description comes from the annotation rather than from a second
     * copy here — one text, one place, whichever way a client reaches it.</p>
     */
    public List<PromptExpose> catalogue() {
        return ORDRE.stream().map(this::expose).toList();
    }

    private PromptExpose expose(String nom) {
        try {
            Method methode = McpPrompts.class.getDeclaredMethod(nom, String.class);
            PromptMessage message = (PromptMessage) methode.invoke(this, (String) null);
            return new PromptExpose(nom, methode.getAnnotation(Prompt.class).description(),
                    message.content().asText().text());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Prompt " + nom + " introuvable ou non appelable", e);
        }
    }

    /** One prompt, as the interface displays it. */
    public record PromptExpose(String nom, String description, String texte) {
    }

    /** Names the edition inside the sentence, or says nothing when the default one is meant. */
    private static String suffixe(String edition) {
        return edition == null || edition.isBlank() ? "" : " de l'édition « " + edition.trim() + " »";
    }

    /** The edition as the subject of a sentence, where saying nothing would leave a hole. */
    private static String designation(String edition) {
        return edition == null || edition.isBlank()
                ? "l'édition par défaut"
                : "l'édition « " + edition.trim() + " »";
    }
}
