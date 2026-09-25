package dev.sylvain.planning.service.consigne;

import dev.sylvain.planning.domain.ConsigneEdition;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.HoraireStand;
import dev.sylvain.planning.domain.IndisponibiliteStand;
import dev.sylvain.planning.domain.JoursFeries;
import dev.sylvain.planning.domain.OuvertureStand;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.analyse.OuvertureStandsAnalyzer;
import dev.sylvain.planning.service.referentiel.HoraireStandResolver;
import dev.sylvain.planning.service.referentiel.HoraireStandResolver.RuledDay;
import dev.sylvain.planning.service.referentiel.HoraireStandResolver.SourceHoraire;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * The three layers that decide the seats a solve will receive, laid side by
 * side for every stand and day, before any solve: the stand's own hours
 * (recurring rules or dated exceptions, as {@link HoraireStandResolver} alone
 * leaves them), the consigne of the day (its band and the stand's reopenings),
 * and what is left once {@link ConsigneResolver} has run — plus the grid's
 * timeslots, drawn behind them.
 *
 * <p>Nothing is recomputed here: the two lists of stands come resolved by the
 * two resolvers themselves ({@code HoraireStandResolver.apply} and
 * {@code StandService.resolve}), and a day is read off them with
 * {@link ConsigneResolver#journeeNominale}, the very reading the consigne
 * layer starts from. The Ouvertures report stays the source of the seats; this
 * only says where each window comes from.</p>
 *
 * <p>Every window is given in minutes from the day's midnight, so the screen
 * places it without parsing a time: a timeslot crossing midnight ends past
 * 1440, and the stand's windows of the next morning are carried over up to
 * where that timeslot ends — the convention of the Journée view.</p>
 *
 * <p>Pure and static, unit-testable on plain objects.</p>
 */
public final class LayerCalendar {

    private static final int MINUTES_PAR_JOUR = 24 * 60;

    private LayerCalendar() {}

    /**
     * One window of a layer.
     *
     * @param effectif the headcount it asks for — the stand's minimum when none
     *                 was typed; on a reopening, {@code null} when the consigne
     *                 inherits it from the hours the band took away
     */
    @Schema(requiredProperties = {"debutMinutes", "finMinutes"})
    public record LayerWindow(int debutMinutes, int finMinutes, Integer effectif) {}

    /**
     * One timeslot of the grid on that day.
     *
     * @param addedByConsigne created by the day's consigne for its reopenings
     */
    @Schema(requiredProperties = {"debutMinutes", "finMinutes", "couverturePause", "addedByConsigne"})
    public record LayerTimeslot(
            Long id,
            LocalTime heureDebut,
            LocalTime heureFin,
            int debutMinutes,
            int finMinutes,
            boolean couverturePause,
            boolean addedByConsigne) {}

    /** The consigne governing a day: the band it closes for every stand, and why. */
    @Schema(requiredProperties = {"debutMinutes", "finMinutes", "fermetureDebut", "motif"})
    public record ConsigneLayer(
            LocalTime fermetureDebut,
            LocalTime fermetureFin,
            int debutMinutes,
            int finMinutes,
            String motif,
            String prereglage) {}

    /**
     * One day of the calendar.
     *
     * @param ferie    the public holiday's name that day, {@code null} otherwise
     * @param consigne {@code null} on a day no consigne governs
     */
    @Schema(requiredProperties = {"date", "jour", "vacations"})
    public record LayerDay(
            LocalDate date, int jour, String ferie, List<LayerTimeslot> vacations, ConsigneLayer consigne) {}

    /**
     * One stand on one day, layer by layer.
     *
     * @param source       which of the stand's own layers decided the day: no rule at all (open by default), a
     *                     recurring rule, or a dated exception
     * @param horaireIds   the rules that decided it, when {@code source} is {@code REGLE}; empty otherwise, or
     *                     when the rules only say the day is not declared
     * @param motif        the reason written on the rule or the exception, if any
     * @param nominal      the stand's own windows that day, before the consigne
     * @param reopenings   the windows the consigne opens this stand on, empty without one
     * @param effective    what is left once the consigne has run: the windows the seats are cut from
     */
    @Schema(requiredProperties = {"date", "source", "horaireIds", "nominal", "reopenings", "effective"})
    public record LayerCell(
            LocalDate date,
            SourceHoraire source,
            List<Long> horaireIds,
            String motif,
            List<LayerWindow> nominal,
            List<LayerWindow> reopenings,
            List<LayerWindow> effective) {}

    @Schema(requiredProperties = {"standId", "effectifMin", "jours"})
    public record LayerRow(String standId, String nom, int effectifMin, List<LayerCell> jours) {}

    /** The calendar: the days of the range that carry a timeslot, and one line per stand. */
    @Schema(requiredProperties = {"jours", "stands"})
    public record OpeningLayers(List<LayerDay> jours, List<LayerRow> stands) {}

    /**
     * Builds the calendar over the days of {@code [du, au]} that carry a
     * timeslot — both bounds optional.
     *
     * @param nominaux  the stands resolved by their rules and exceptions only
     * @param effectifs the same stands with the consignes laid on top, matched by id
     */
    public static OpeningLayers build(
            List<Stand> nominaux,
            List<Stand> effectifs,
            Collection<ConsigneEdition> consignes,
            List<Creneau> creneaux,
            LocalDate du,
            LocalDate au) {
        Map<LocalDate, ConsigneEdition> consigneParDate = new HashMap<>();
        for (ConsigneEdition consigne : consignes) {
            consigneParDate.put(consigne.date(), consigne);
        }
        Map<LocalDate, List<Creneau>> creneauxParJour = OuvertureStandsAnalyzer.creneauxByDay(creneaux);
        List<LayerDay> jours = new ArrayList<>();
        Map<LocalDate, Integer> lendemains = new HashMap<>();
        creneauxParJour.forEach((date, duJour) -> {
            if ((du != null && date.isBefore(du)) || (au != null && date.isAfter(au))) {
                return;
            }
            ConsigneEdition consigne = consigneParDate.get(date);
            Set<Long> ajoutes = consigne == null ? Set.of() : Set.copyOf(consigne.creneauxAjoutes());
            List<LayerTimeslot> vacations = new ArrayList<>();
            int lendemain = 0;
            for (Creneau creneau : duJour) {
                int[] bornes = interval(creneau.getHeureDebut(), creneau.getHeureFin());
                lendemain = Math.max(lendemain, bornes[1] - MINUTES_PAR_JOUR);
                vacations.add(new LayerTimeslot(
                        creneau.getId(),
                        creneau.getHeureDebut(),
                        creneau.getHeureFin(),
                        bornes[0],
                        bornes[1],
                        creneau.isCouverturePause(),
                        creneau.getId() != null && ajoutes.contains(creneau.getId())));
            }
            lendemains.put(date, lendemain);
            jours.add(new LayerDay(
                    date,
                    duJour.get(0).getJour(),
                    JoursFeries.label(date).orElse(null),
                    vacations,
                    consigne == null ? null : consigneLayer(consigne)));
        });

        Map<String, Stand> effectifsParId = new HashMap<>();
        for (Stand stand : effectifs) {
            effectifsParId.put(stand.getId(), stand);
        }
        List<LayerRow> lignes = new ArrayList<>();
        for (Stand nominal : nominaux) {
            Stand effectif = effectifsParId.getOrDefault(nominal.getId(), nominal);
            List<LayerCell> cellules = new ArrayList<>();
            for (LayerDay jour : jours) {
                cellules.add(cell(
                        nominal, effectif, jour.date(), lendemains.get(jour.date()), consigneParDate.get(jour.date())));
            }
            lignes.add(new LayerRow(nominal.getId(), nominal.getNom(), nominal.getEffectifMin(), cellules));
        }
        return new OpeningLayers(jours, lignes);
    }

    private static ConsigneLayer consigneLayer(ConsigneEdition consigne) {
        int[] bande = ConsigneResolver.minutes(consigne.fermetureDebut(), consigne.fermetureFin());
        return new ConsigneLayer(
                consigne.fermetureDebut(),
                consigne.fermetureFin(),
                bande[0],
                bande[1],
                consigne.motif(),
                consigne.prereglage());
    }

    private static LayerCell cell(
            Stand nominal, Stand effectif, LocalDate date, int lendemain, ConsigneEdition consigne) {
        SourceHoraire source = HoraireStandResolver.sourceOfDay(nominal, date);
        List<Long> horaireIds = List.of();
        String motif = null;
        if (source == SourceHoraire.REGLE) {
            RuledDay jour = HoraireStandResolver.resolveDayWithRules(nominal.getHoraires(), date);
            if (jour != null) {
                horaireIds = jour.rules().stream()
                        .map(HoraireStand::getId)
                        .filter(Objects::nonNull)
                        .toList();
                motif = jour.motif();
            }
        } else if (source == SourceHoraire.EXCEPTION) {
            motif = Stream.concat(
                            nominal.getOuvertures().stream()
                                    .filter(ouverture -> date.equals(ouverture.getDate()))
                                    .map(OuvertureStand::getMotif),
                            nominal.getIndisponibilites().stream()
                                    .filter(fermeture -> date.equals(fermeture.getDate()))
                                    .map(IndisponibiliteStand::getMotif))
                    .filter(Objects::nonNull)
                    .findFirst()
                    .orElse(null);
        }
        List<LayerWindow> reopenings = new ArrayList<>();
        if (consigne != null) {
            for (ConsigneEdition.Ouverture ouverture : consigne.openingsOf(nominal.getId())) {
                if (ouverture.debut() != null) {
                    int[] bornes = ConsigneResolver.minutes(ouverture.debut(), ouverture.fin());
                    reopenings.add(new LayerWindow(bornes[0], bornes[1], ouverture.effectif()));
                }
            }
        }
        return new LayerCell(
                date,
                source,
                horaireIds,
                motif,
                windows(nominal, date, lendemain),
                reopenings,
                windows(effectif, date, lendemain));
    }

    /**
     * The stand's open windows on {@code date}, then those of the next morning
     * up to {@code lendemain} minutes, shifted past midnight; two stretches
     * meeting at midnight at the same headcount are one.
     */
    static List<LayerWindow> windows(Stand stand, LocalDate date, int lendemain) {
        List<LayerWindow> fenetres = new ArrayList<>();
        for (ConsigneResolver.Segment segment : ConsigneResolver.journeeNominale(stand, date)) {
            fenetres.add(new LayerWindow(segment.bornes()[0], segment.bornes()[1], headcount(stand, segment)));
        }
        fenetres.sort(Comparator.comparingInt(LayerWindow::debutMinutes));
        if (lendemain > 0) {
            for (ConsigneResolver.Segment segment : ConsigneResolver.journeeNominale(stand, date.plusDays(1))) {
                int debut = segment.bornes()[0];
                int fin = Math.min(segment.bornes()[1], lendemain);
                if (debut >= fin) {
                    continue;
                }
                LayerWindow suite =
                        new LayerWindow(debut + MINUTES_PAR_JOUR, fin + MINUTES_PAR_JOUR, headcount(stand, segment));
                LayerWindow derniere = fenetres.isEmpty() ? null : fenetres.get(fenetres.size() - 1);
                if (derniere != null
                        && derniere.finMinutes() == suite.debutMinutes()
                        && Objects.equals(derniere.effectif(), suite.effectif())) {
                    fenetres.set(
                            fenetres.size() - 1,
                            new LayerWindow(derniere.debutMinutes(), suite.finMinutes(), derniere.effectif()));
                } else {
                    fenetres.add(suite);
                }
            }
        }
        return fenetres;
    }

    private static int headcount(Stand stand, ConsigneResolver.Segment segment) {
        return segment.effectif() != null ? segment.effectif() : stand.getEffectifMin();
    }

    /** {@code [debut, fin]} in minutes from the start day's midnight; an end at or before the start crosses it. */
    private static int[] interval(LocalTime debut, LocalTime fin) {
        int d = debut.toSecondOfDay() / 60;
        int f = fin.toSecondOfDay() / 60;
        return new int[] {d, f > d ? f : f + MINUTES_PAR_JOUR};
    }
}
