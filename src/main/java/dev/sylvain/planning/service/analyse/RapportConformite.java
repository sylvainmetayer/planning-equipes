package dev.sylvain.planning.service.analyse;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * The compliance report of one edition, read from its <b>published</b> plan —
 * what the animateurs were told, therefore what binds the organisation — and
 * meant to be shown to a labour inspector.
 *
 * <p>It measures nothing of its own: the hours are {@link PlanningHoursService}'s,
 * the ceilings those of {@code ParametresLegaux} that the legal rules read, and
 * the breaches are the matches of the legal rules themselves. A figure here and
 * the same figure on the Heures page are the one number.</p>
 *
 * @param publieLe  when the plan it reads was published
 * @param genereLe  when this report was written
 * @param semaines  the ISO weeks of the plan, sorted, the keys of every {@code heuresParSemaine}
 * @param regles    every legal rule, with its number of breaches (zero is the expected value)
 * @param personnes every animateur holding at least one seat, by name
 */
@Schema(
        requiredProperties = {
            "editionId",
            "publieLe",
            "genereLe",
            "plafonds",
            "synthese",
            "semaines",
            "regles",
            "personnes"
        })
public record RapportConformite(
        String editionId,
        String editionNom,
        Instant publieLe,
        Instant genereLe,
        Plafonds plafonds,
        Synthese synthese,
        List<String> semaines,
        List<Regle> regles,
        List<Personne> personnes) {

    /**
     * The ceilings the report was checked against, in hours, as the edition sets them.
     *
     * @param ecartsAuxDefauts what differs from the legal default, one sentence each; empty when nothing does
     */
    @Schema(
            name = "PlafondsConformite",
            requiredProperties = {
                "hebdomadaireMajeurHeures",
                "hebdomadaireMineurHeures",
                "vacationHeures",
                "reposQuotidienHeures",
                "ecartsAuxDefauts"
            })
    public record Plafonds(
            double hebdomadaireMajeurHeures,
            double hebdomadaireMineurHeures,
            double vacationHeures,
            double reposQuotidienHeures,
            List<String> ecartsAuxDefauts) {}

    /**
     * @param ecarts                breaches of the legal rules, all together
     * @param semainesEnDepassement weeks over the ceiling that applied, all people together
     * @param heuresTravail         effective work over the whole plan
     * @param heuresDimanche        opening span on Sundays
     * @param heuresFerie           opening span on public holidays
     * @param heuresNuit            opening span after 22:00
     */
    @Schema(
            name = "SyntheseConformite",
            requiredProperties = {
                "personnes",
                "mineurs",
                "ecarts",
                "semainesEnDepassement",
                "heuresTravail",
                "heuresDimanche",
                "heuresFerie",
                "heuresNuit"
            })
    public record Synthese(
            int personnes,
            int mineurs,
            int ecarts,
            int semainesEnDepassement,
            double heuresTravail,
            double heuresDimanche,
            double heuresFerie,
            double heuresNuit) {}

    /** One legal rule: its name (the catalogue's), how it reads, and how many times the plan breaks it. */
    @Schema(
            name = "RegleConformite",
            requiredProperties = {"nom", "libelle", "mineurs", "ecarts"})
    public record Regle(String nom, String libelle, boolean mineurs, int ecarts) {}

    /**
     * One animateur.
     *
     * @param mineur                  a minor on at least one day they hold a seat — the weekly ceiling then is the minors'
     * @param heuresParSemaine        effective work per ISO week of {@code semaines}
     * @param plafondHebdomadaire     the ceiling that applies to them, in hours
     * @param semainesEnDepassement   the weeks over it
     * @param reposQuotidienMinHeures the shortest rest observed between two worked days, {@code null} under two days
     * @param ecartsDetail            one line per breach of a legal rule naming them
     */
    @Schema(
            name = "PersonneConformite",
            requiredProperties = {
                "animateurId",
                "nom",
                "mineur",
                "heuresParSemaine",
                "heuresTotal",
                "plafondHebdomadaire",
                "semainesEnDepassement",
                "heuresDimanche",
                "heuresFerie",
                "heuresNuit",
                "ecartsDetail"
            })
    public record Personne(
            String animateurId,
            String nom,
            boolean mineur,
            Map<String, Double> heuresParSemaine,
            double heuresTotal,
            double plafondHebdomadaire,
            List<String> semainesEnDepassement,
            double heuresDimanche,
            double heuresFerie,
            double heuresNuit,
            Double reposQuotidienMinHeures,
            List<Breach> ecartsDetail) {}

    /** One breach of a legal rule, as a sentence. */
    @Schema(
            name = "BreachConformite",
            requiredProperties = {"regle", "libelle", "texte"})
    public record Breach(String regle, String libelle, String texte) {}
}
