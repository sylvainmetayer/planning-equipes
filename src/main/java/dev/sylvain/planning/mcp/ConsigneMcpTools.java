package dev.sylvain.planning.mcp;

import dev.sylvain.planning.domain.ConsigneEdition;
import dev.sylvain.planning.domain.ConsigneEdition.Fenetre;
import dev.sylvain.planning.domain.ConsigneEdition.Ouverture;
import dev.sylvain.planning.domain.PrereglageConsigne;
import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.consigne.ConsigneService;
import dev.sylvain.planning.service.consigne.ConsigneService.ApercuJour;
import dev.sylvain.planning.service.consigne.ConsigneService.ApercuLevee;
import dev.sylvain.planning.service.consigne.ConsigneService.Demande;
import dev.sylvain.planning.service.consigne.ConsigneService.Preselection;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * MCP tools mirroring {@code ConsigneResource} (issue #4): an arrêté falls at
 * 20h, and the question is whether the plan can be turned around before the
 * night — list, propose the stands, simulate, lay down, extend, lift, and
 * the presets that make each wave the same gesture.
 *
 * <p>Openings travel as one line of text, {@code STAND=HH:MM-HH:MM} followed
 * by {@code *N} for a headcount, separated by commas; a stand opened on two
 * windows is named twice. Windows of the day travel as {@code HH:MM-HH:MM},
 * the end omitted for « jusqu'à minuit ». Nothing here names a person: the
 * previews are counts.</p>
 */
@EditionCiblee
@RefusMetier
@Journalise
@ApplicationScoped
public class ConsigneMcpTools {

    @Inject
    ConsigneService consignes;

    @Tool(
            description = "Liste les consignes de l'édition — par date, la bande horaire fermée pour tous les stands, "
                    + "le motif, les fenêtres de compensation et les stands rouverts — et les préréglages "
                    + "(« Plan canicule ») dont elles se posent.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    ConsignesView lister_consignes(
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        ConsigneService.EtatConsignes etat = consignes.etat();
        return new ConsignesView(
                etat.aujourdhui(),
                etat.consignes().stream().map(ConsigneMcpTools::toView).toList(),
                etat.prereglages());
    }

    @Tool(
            description = "Les stands lus contre une bande fermée sur une date, sans rien écrire : ce que la bande "
                    + "prend à chacun (minutes perdues), l'effectif hérité, ceux proposés cochés pour rouvrir, et ceux "
                    + "écartés parce qu'ils ont posé leurs horaires à la main ce jour-là.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    Preselection consulter_preselection_consigne(
            @ToolArg(description = "Date (AAAA-MM-JJ)") String date,
            @ToolArg(description = "Début de la bande fermée pour tous (HH:MM)") String fermetureDebut,
            @ToolArg(description = "Fin de la bande (HH:MM) ; omise = jusqu'à minuit", required = false)
                    String fermetureFin,
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        return consignes.preselection(
                McpArgs.date(date, "date"), McpArgs.heure(fermetureDebut, "fermetureDebut"), timeOrNull(fermetureFin));
    }

    @Tool(
            description = "Simule une consigne sans rien écrire : par jour, sièges et minutes avant et après, "
                    + "créneaux à ajouter ou à retirer, vacations qui perdent leurs sièges, stands entrants et "
                    + "sortants, mineurs exclus des fenêtres ajoutées, validation de relecture retirée, verrous et "
                    + "règles ad hoc touchés, personnes du plan assises dans la bande.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    List<ApercuJour> simuler_consigne(
            @ToolArg(description = "Dates concernées (AAAA-MM-JJ), séparées par des virgules") String dates,
            @ToolArg(description = "Début de la bande fermée pour tous (HH:MM)") String fermetureDebut,
            @ToolArg(description = "Fin de la bande (HH:MM) ; omise = jusqu'à minuit", required = false)
                    String fermetureFin,
            @ToolArg(description = "Motif, repris tel quel (« arrêté préfectoral du … »)") String motif,
            @ToolArg(
                            description =
                                    "Fenêtres de compensation par défaut « HH:MM-HH:MM », séparées par des virgules",
                            required = false)
                    String fenetres,
            @ToolArg(
                            description = "Ouvertures « STAND=HH:MM-HH:MM[*effectif] », séparées par des virgules",
                            required = false)
                    String ouvertures,
            @ToolArg(description = "Nom du préréglage d'origine, s'il y en a un", required = false) String prereglage,
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        return consignes.apercu(demande(dates, fermetureDebut, fermetureFin, motif, fenetres, ouvertures, prereglage));
    }

    @Tool(
            description = "Pose une consigne sur une ou plusieurs dates à venir — ou remplace celle qu'une date porte "
                    + "déjà : ferme la bande pour tous les stands, rouvre les stands nommés sur leurs fenêtres, ajoute "
                    + "à la grille les créneaux que ces fenêtres exigent. Rien n'est supprimé de la grille nominale. "
                    + "Prolonger une alerte, c'est le même appel avec les dates ajoutées. Retire la validation de "
                    + "relecture des journées touchées. Relancer ensuite resoudre_incremental, puis etat_publication.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = false,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    List<ApercuJour> appliquer_consigne(
            @ToolArg(description = "Dates concernées (AAAA-MM-JJ), séparées par des virgules") String dates,
            @ToolArg(description = "Début de la bande fermée pour tous (HH:MM)") String fermetureDebut,
            @ToolArg(description = "Fin de la bande (HH:MM) ; omise = jusqu'à minuit", required = false)
                    String fermetureFin,
            @ToolArg(description = "Motif, repris tel quel (« arrêté préfectoral du … »)") String motif,
            @ToolArg(
                            description =
                                    "Fenêtres de compensation par défaut « HH:MM-HH:MM », séparées par des virgules",
                            required = false)
                    String fenetres,
            @ToolArg(
                            description = "Ouvertures « STAND=HH:MM-HH:MM[*effectif] », séparées par des virgules",
                            required = false)
                    String ouvertures,
            @ToolArg(description = "Nom du préréglage d'origine, s'il y en a un", required = false) String prereglage,
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        return consignes.poser(demande(dates, fermetureDebut, fermetureFin, motif, fenetres, ouvertures, prereglage));
    }

    @Tool(
            description = "Simule la levée d'une consigne sans rien écrire : par date, les créneaux ajoutés qui "
                    + "seraient retirés avec leurs sièges, et combien de personnes y sont assises.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    List<ApercuLevee> simuler_levee_consigne(
            @ToolArg(description = "Dates à lever (AAAA-MM-JJ), séparées par des virgules") String dates,
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        return consignes.apercuLevee(dates(dates));
    }

    @Tool(
            description = "Lève la consigne des dates indiquées, qui doivent être à venir : les stands retrouvent "
                    + "leurs horaires, les créneaux que la consigne avait ajoutés sont retirés avec leurs sièges, la "
                    + "validation de relecture des journées est retirée. Un jour déjà travaillé garde la consigne qui "
                    + "l'a gouverné. Relancer ensuite resoudre_incremental : la règle de stabilité rend les "
                    + "après-midis à leurs titulaires.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = false,
                            destructiveHint = true,
                            idempotentHint = false,
                            openWorldHint = false))
    LeveeResult lever_consigne(
            @ToolArg(description = "Dates à lever (AAAA-MM-JJ), séparées par des virgules") String dates,
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        List<LocalDate> levees = dates(dates);
        consignes.lever(levees);
        return new LeveeResult(levees, true);
    }

    @Tool(
            description = "Crée ou remplace un préréglage de consigne (« Plan canicule : 12h-18h fermé, soir "
                    + "18h-22h ») : la bande, le motif et les fenêtres de compensation par défaut, mémorisés sur "
                    + "l'édition et copiés à sa duplication. Validé à froid, sans rien poser.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = false,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    PrereglageConsigne definir_prereglage_consigne(
            @ToolArg(description = "Nom du préréglage") String nom,
            @ToolArg(description = "Début de la bande fermée pour tous (HH:MM)") String fermetureDebut,
            @ToolArg(description = "Fin de la bande (HH:MM) ; omise = jusqu'à minuit", required = false)
                    String fermetureFin,
            @ToolArg(description = "Motif proposé aux consignes faites de ce préréglage") String motif,
            @ToolArg(
                            description =
                                    "Fenêtres de compensation par défaut « HH:MM-HH:MM », séparées par des virgules",
                            required = false)
                    String fenetres,
            @ToolArg(description = "Id du préréglage à remplacer ; omis = création", required = false) String id,
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        return consignes.savePrereglage(new PrereglageConsigne(
                id,
                nom,
                McpArgs.heure(fermetureDebut, "fermetureDebut"),
                timeOrNull(fermetureFin),
                motif,
                dayWindows(fenetres),
                null));
    }

    @Tool(
            description =
                    "Supprime un préréglage de consigne. Les consignes déjà posées avec lui restent telles quelles.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = false,
                            destructiveHint = true,
                            idempotentHint = true,
                            openWorldHint = false))
    SuppressionResult supprimer_prereglage_consigne(
            @ToolArg(description = "Id du préréglage") String id,
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        consignes.deletePrereglage(id);
        return new SuppressionResult(id, true);
    }

    /* -------------------------------- parsing -------------------------------- */

    static Demande demande(
            String dates,
            String fermetureDebut,
            String fermetureFin,
            String motif,
            String fenetres,
            String ouvertures,
            String prereglage) {
        return new Demande(
                dates(dates),
                McpArgs.heure(fermetureDebut, "fermetureDebut"),
                timeOrNull(fermetureFin),
                motif,
                prereglage,
                dayWindows(fenetres),
                ouvertures(ouvertures));
    }

    static List<LocalDate> dates(String dates) {
        if (dates == null || dates.isBlank()) {
            throw new BusinessError.Invalid("dates est requis, ex. « 2027-02-03,2027-02-04 »");
        }
        List<LocalDate> result = new ArrayList<>();
        for (String morceau : dates.split(",")) {
            if (!morceau.isBlank()) {
                result.add(McpArgs.date(morceau.trim(), "dates"));
            }
        }
        return result;
    }

    static LocalTime timeOrNull(String valeur) {
        return valeur == null || valeur.isBlank() ? null : McpArgs.heure(valeur.trim(), "heure");
    }

    /** {@code « 08:00-10:00,18:00- »}: windows of the day, the end omitted for midnight. */
    static List<Fenetre> dayWindows(String fenetres) {
        if (fenetres == null || fenetres.isBlank()) {
            return List.of();
        }
        List<Fenetre> result = new ArrayList<>();
        for (String morceau : fenetres.split(",")) {
            String fenetre = morceau.trim();
            if (fenetre.isEmpty()) {
                continue;
            }
            result.add(fenetre(fenetre, "fenetres"));
        }
        return result;
    }

    /** {@code « BOURSE=18:00-22:00*2,ENFANTS=18:00-20:00 »}: one opening per entry. */
    static List<Ouverture> ouvertures(String ouvertures) {
        if (ouvertures == null || ouvertures.isBlank()) {
            return List.of();
        }
        List<Ouverture> result = new ArrayList<>();
        for (String morceau : ouvertures.split(",")) {
            String entree = morceau.trim();
            if (entree.isEmpty()) {
                continue;
            }
            int egal = entree.indexOf('=');
            if (egal <= 0) {
                throw new BusinessError.Invalid("Ouverture illisible : « " + entree
                        + " » (attendu STAND=HH:MM-HH:MM, puis *N pour l'effectif)");
            }
            String standId = entree.substring(0, egal).trim();
            String reste = entree.substring(egal + 1).trim();
            Integer effectif = null;
            int etoile = reste.indexOf('*');
            if (etoile >= 0) {
                String valeur = reste.substring(etoile + 1).trim();
                try {
                    effectif = Integer.valueOf(valeur);
                } catch (NumberFormatException e) {
                    throw new BusinessError.Invalid("Effectif illisible dans « " + entree + " » : " + valeur);
                }
                reste = reste.substring(0, etoile).trim();
            }
            Fenetre fenetre = fenetre(reste, "ouvertures");
            result.add(new Ouverture(standId, fenetre.debut(), fenetre.fin(), effectif));
        }
        return result;
    }

    private static Fenetre fenetre(String texte, String champ) {
        int tiret = texte.indexOf('-');
        if (tiret <= 0) {
            throw new BusinessError.Invalid("Fenêtre illisible dans " + champ + " : « " + texte
                    + " » (attendu HH:MM-HH:MM, ou HH:MM- jusqu'à minuit)");
        }
        LocalTime debut = McpArgs.heure(texte.substring(0, tiret).trim(), champ);
        LocalTime fin = timeOrNull(texte.substring(tiret + 1));
        return new Fenetre(debut, fin);
    }

    /* --------------------------------- views --------------------------------- */

    static ConsigneView toView(ConsigneEdition consigne) {
        return new ConsigneView(
                consigne.date(),
                consigne.fermetureDebut(),
                consigne.fermetureFin(),
                consigne.motif(),
                consigne.prereglage(),
                consigne.fenetres(),
                consigne.ouvertures(),
                consigne.creneauxAjoutes());
    }

    /**
     * One consigne. The motif is the organiser's description of the decision
     * (« arrêté préfectoral du 3 août »), never a person's text.
     */
    public record ConsigneView(
            LocalDate date,
            LocalTime fermetureDebut,
            LocalTime fermetureFin,
            String motif,
            String prereglage,
            List<Fenetre> fenetres,
            List<Ouverture> ouvertures,
            List<Long> creneauxAjoutes) {}

    /** The consignes, the presets, and the day the server counts from. */
    public record ConsignesView(
            LocalDate aujourdhui, List<ConsigneView> consignes, List<PrereglageConsigne> prereglages) {}

    /** The dates lifted. */
    public record LeveeResult(List<LocalDate> dates, boolean levee) {}
}
