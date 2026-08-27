package dev.sylvain.planning.mcp;

import java.util.stream.Collectors;

import dev.sylvain.planning.solver.ConstraintCatalog;
import dev.sylvain.planning.solver.ConstraintCatalog.ConstraintDefinition;
import io.quarkiverse.mcp.server.Resource;
import io.quarkiverse.mcp.server.TextResourceContents;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * The two pieces of context an assistant cannot deduce from the tools: what
 * the solver actually optimises, and what the words mean.
 *
 * <p>Resources rather than tools because they are read before the work, not
 * during it — a client attaches them once and every later call is better
 * informed, where a tool has to be guessed at and called.</p>
 *
 * <p>The constraint catalogue is <b>generated from
 * {@link ConstraintCatalog}</b>, not shipped as a copy of
 * {@code docs/contraintes.md}: it is the very list the solver runs, so it
 * cannot describe rules the solver does not apply, nor miss one added last
 * week. The vocabulary is the one place a text is written by hand — it
 * summarises {@code docs/domaine.md} for a reader who will never see the Java
 * model.</p>
 */
@ApplicationScoped
public class McpResources {

    @Resource(uri = "planning://contraintes", name = "catalogue-contraintes",
            description = "Le catalogue des contraintes du solveur : niveau, catégorie et ce que chacune "
                    + "cherche à obtenir. Généré depuis le code du solveur, donc toujours à jour.",
            mimeType = "text/markdown")
    TextResourceContents catalogueContraintes() {
        String tableau = ConstraintCatalog.definitions().stream()
                .sorted((premiere, seconde) -> premiere.niveau().compareTo(seconde.niveau()))
                .map(McpResources::ligne)
                .collect(Collectors.joining("\n"));
        return TextResourceContents.create("planning://contraintes", """
                # Contraintes du solveur

                Trois niveaux, du plus contraignant au moins contraignant. Une contrainte HARD violée
                rend le planning **infaisable** : aucun compromis ne la rachète, et c'est le seul
                niveau qui doit atteindre zéro. MEDIUM et SOFT s'échangent entre eux — le solveur y
                cherche le meilleur compromis, pas la perfection.

                Chaque contrainte peut être désactivée (`desactiver_contrainte`) ou pondérée
                (`modifier_poids_contrainte`) pour une édition, sans toucher aux autres. Le niveau,
                lui, n'est pas réglable.

                | Contrainte | Niveau | Catégorie | Ce qu'elle cherche |
                | --- | --- | --- | --- |
                %s
                """.formatted(tableau));
    }

    @Resource(uri = "planning://vocabulaire", name = "vocabulaire-planning",
            description = "Le vocabulaire métier de l'application : stand, créneau, poste, vacation, "
                    + "amplitude, typologie, découpage, édition. À lire avant de manipuler les outils.",
            mimeType = "text/markdown")
    TextResourceContents vocabulaire() {
        return TextResourceContents.create("planning://vocabulaire", """
                # Vocabulaire

                Les mots ci-dessous sont ceux du classeur de l'organisateur ; les outils MCP les
                reprennent tels quels.

                | Mot | Ce que c'est |
                | --- | --- |
                | **Animateur** | une personne à planifier. Sur MCP elle n'a **qu'un id** : ni nom, ni prénom, ni date de naissance ne sortent d'ici. Le statut majeur/mineur en est dérivé, parce que les contraintes légales en dépendent. |
                | **Stand** | un lieu d'activité à armer. Il porte un effectif minimum et maximum, des typologies proposées, et ses heures d'ouverture. |
                | **Typologie** | une catégorie de jeu. C'est un référentiel modifiable, pas une liste figée : les compétences des animateurs et les typologies proposées par les stands s'y réfèrent. |
                | **Emplacement** | l'endroit physique où un stand se trouve, utilisé pour limiter les déplacements. |
                | **Créneau** | une tranche horaire datée. Selon l'étape, la grille contient des **amplitudes** (la journée d'ouverture, à découper) ou des **vacations** (les tranches finales sur lesquelles on affecte). |
                | **Amplitude** | l'ouverture d'une journée, de bout en bout. À ne pas confondre avec le mot anglais. |
                | **Vacation** | une tranche de travail d'un animateur. Là encore, faux ami : rien à voir avec des vacances. |
                | **Découpage** | l'opération qui remplace les amplitudes par des vacations, selon les paramètres de découpage. |
                | **Poste** | un siège à pourvoir : un stand, un créneau, une place. C'est l'unité que le solveur affecte, et ce que comptent `volumes` et `synthese_affectations`. |
                | **Édition** | un événement complet et son référentiel. Chaque outil accepte un argument `edition` ; sans lui, il travaille dans l'édition par défaut. Une variante d'une édition est **une autre édition**. |
                | **Verrouillage** | une partie du planning que le solveur n'a plus le droit de déplacer. |
                | **Instantané** | une copie datée d'un planning résolu, qu'on peut comparer et restaurer. |

                ## L'ordre dans lequel les choses se font

                1. le référentiel : emplacements, typologies, stands, animateurs ;
                2. la grille de créneaux, en amplitudes, puis son découpage en vacations ;
                3. la vérification : `valider_creneaux`, `analyser_ouvertures_stands`,
                   `analyser_effectifs`, `analyser_faisabilite` ;
                4. la résolution : `lancer_solveur`, puis le diagnostic
                   `expliquer_echec_contraintes_dures` ;
                5. la retouche : `verrouiller` ce qui est bon, `resoudre_incremental` pour le reste,
                   `suggerer_reparations` et `affecter_poste` pour un siège isolé.
                """);
    }

    private static String ligne(ConstraintDefinition definition) {
        return "| `" + definition.name() + "` | " + definition.niveau().name() + " | "
                + definition.categorie() + " | " + definition.description() + " |";
    }
}
