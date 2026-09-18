package dev.sylvain.planning.service.journee;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.service.journee.ChangementsJournee.AnimateurChange;
import dev.sylvain.planning.service.journee.ChangementsJournee.AnimateurLine;
import dev.sylvain.planning.service.journee.ChangementsJournee.Holder;
import dev.sylvain.planning.service.journee.ChangementsJournee.ReferenceChangements;
import dev.sylvain.planning.service.journee.ChangementsJournee.SeatChangeType;
import dev.sylvain.planning.service.journee.ChangementsJournee.SeatLine;
import dev.sylvain.planning.service.publication.PlanPublieService;
import dev.sylvain.planning.service.publication.PublicationDiffService;
import dev.sylvain.planning.service.publication.PublicationDiffService.ChangementAnimateur;
import dev.sylvain.planning.service.publication.PublicationDiffService.Identite;
import dev.sylvain.planning.service.solve.PlanSnapshotService;
import dev.sylvain.planning.service.solve.PlanningPersistenceService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What changed on one day between the persisted plan and a reference — the
 * last published plan, or the plan the last solve started from.
 *
 * <p>Nothing here is a new comparison. The seat-by-seat reading pairs the two
 * plans on the natural key of a shift — stand, day, hours — as the stability
 * rule of the published plan does, and the seats of one cell are
 * interchangeable, as the incremental re-solve already counts them. The
 * person-by-person reading <b>is</b> the publication's diff, narrowed to the
 * date: the sentences are the ones the mail would carry, so the tab and the
 * Publication screen cannot tell two stories about the same day.</p>
 *
 * <p>A reference that does not exist is said, never replaced by an empty plan:
 * « aucun changement » and « rien à comparer » are two different answers, and
 * the second one is the honest one before the first publication.</p>
 */
@ApplicationScoped
public class ChangementsJourneeService {

    @Inject
    PlanningPersistenceService persistenceService;

    @Inject
    PlanPublieService planPublieService;

    @Inject
    PlanSnapshotService snapshotService;

    @Inject
    PublicationDiffService diffService;

    /**
     * The changes of {@code jour} against {@code reference}.
     *
     * @param reference {@code null} picks the publication when one exists, the
     *                  last solve otherwise — what a screen opens on
     */
    public ChangementsJournee changements(LocalDate jour, ReferenceChangements reference) {
        ReferenceChangements retenue = reference == null ? defaultReference() : reference;
        PlanningEvenement courant = persistenceService.loadPersistedPlanning();
        return switch (retenue) {
            case PUBLICATION -> {
                PlanSnapshotService.SnapshotMeta derniere = planPublieService.lastPublication();
                if (derniere == null) {
                    yield ChangementsJournee.withoutReference(jour, retenue);
                }
                yield compare(jour, retenue, derniere.publieLe(), planPublieService.planPublie(), courant, diffService);
            }
            case RESOLUTION -> {
                PlanSnapshotService.SnapshotDetail detail = snapshotService.loadLastBeforeSolve();
                if (detail == null) {
                    yield ChangementsJournee.withoutReference(jour, retenue);
                }
                PlanningEvenement avant = persistenceService.assemblerPlanning(detail.affectations().stream()
                        .map(PlanSnapshotService::seat)
                        .toList());
                yield compare(jour, retenue, detail.meta().creeLe(), avant, courant, diffService);
            }
        };
    }

    private ReferenceChangements defaultReference() {
        return planPublieService.jamaisPublie() ? ReferenceChangements.RESOLUTION : ReferenceChangements.PUBLICATION;
    }

    /**
     * The comparison itself, on two plans already resolved against the same
     * référentiel — pure, so it is tested on hand-built plans.
     *
     * @param avant   the reference plan
     * @param courant the persisted plan
     */
    public static ChangementsJournee compare(
            LocalDate jour,
            ReferenceChangements reference,
            Instant referenceLe,
            PlanningEvenement avant,
            PlanningEvenement courant,
            PublicationDiffService diff) {
        Map<String, Animateur> animateurs = animateursById(courant);
        animateursById(avant).forEach(animateurs::putIfAbsent);

        List<SeatLine> parVacation = seatLines(jour, avant, courant, animateurs);
        List<AnimateurLine> parAnimateur = animateurLines(jour, avant, courant, animateurs, diff);

        int nouveaux = 0;
        int retires = 0;
        int remplaces = 0;
        for (SeatLine ligne : parVacation) {
            switch (ligne.type()) {
                case NOUVEAU -> nouveaux++;
                case RETIRE -> retires++;
                case REMPLACE -> remplaces++;
            }
        }
        return new ChangementsJournee(
                jour,
                reference,
                true,
                referenceLe,
                nouveaux,
                retires,
                remplaces,
                parAnimateur.size(),
                parVacation,
                parAnimateur);
    }

    /* ---------------------------- Seat by seat ---------------------------- */

    /** The seats of the day, grouped by the cell they fill: one stand, one window. */
    private record Cell(String standId, String standNom, LocalDate date, LocalTime debut, LocalTime fin) {

        String key() {
            return standId + "|" + date + "|" + debut + "|" + fin;
        }

        static Cell of(PosteAffectation poste) {
            return new Cell(
                    poste.getStand().getId(),
                    poste.getStand().getNom(),
                    poste.getCreneau().getDate(),
                    poste.heureDebutEffectif(),
                    poste.heureFinEffectif());
        }
    }

    private static List<SeatLine> seatLines(
            LocalDate jour, PlanningEvenement avant, PlanningEvenement courant, Map<String, Animateur> animateurs) {
        Map<String, Cell> cells = new LinkedHashMap<>();
        Map<String, List<String>> holdersAvant = holdersByCell(jour, avant, cells);
        Map<String, List<String>> holdersApres = holdersByCell(jour, courant, cells);

        List<SeatLine> lignes = new ArrayList<>();
        for (Cell cell : cells.values()) {
            List<String> retraits = new ArrayList<>(holdersAvant.getOrDefault(cell.key(), List.of()));
            List<String> ajouts = new ArrayList<>(holdersApres.getOrDefault(cell.key(), List.of()));
            // The seats of one cell are interchangeable: somebody kept in the
            // cell is not a change, whichever poste id they sit on now.
            for (String id : new ArrayList<>(retraits)) {
                if (ajouts.remove(id)) {
                    retraits.remove(id);
                }
            }
            int paires = Math.min(retraits.size(), ajouts.size());
            for (int i = 0; i < paires; i++) {
                lignes.add(line(cell, holder(retraits.get(i), animateurs), holder(ajouts.get(i), animateurs)));
            }
            for (int i = paires; i < retraits.size(); i++) {
                lignes.add(line(cell, holder(retraits.get(i), animateurs), null));
            }
            for (int i = paires; i < ajouts.size(); i++) {
                lignes.add(line(cell, null, holder(ajouts.get(i), animateurs)));
            }
        }
        lignes.sort(Comparator.comparing(SeatLine::heureDebut, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(SeatLine::standNom, Comparator.nullsFirst(String.CASE_INSENSITIVE_ORDER))
                .thenComparing(SeatLine::type));
        return List.copyOf(lignes);
    }

    private static SeatLine line(Cell cell, Holder avant, Holder apres) {
        SeatChangeType type = avant == null
                ? SeatChangeType.NOUVEAU
                : apres == null ? SeatChangeType.RETIRE : SeatChangeType.REMPLACE;
        return new SeatLine(cell.standId(), cell.standNom(), cell.date(), cell.debut(), cell.fin(), avant, apres, type);
    }

    /**
     * Who holds the seats of each cell of {@code jour}, sorted so two plans
     * seating the same people in another order read as unchanged. Empty seats
     * hold nobody and are not listed: a chair nobody sits on names no change
     * until somebody does.
     */
    private static Map<String, List<String>> holdersByCell(
            LocalDate jour, PlanningEvenement planning, Map<String, Cell> cells) {
        Map<String, List<String>> holders = new LinkedHashMap<>();
        if (planning == null || planning.getPostes() == null) {
            return holders;
        }
        for (PosteAffectation poste : planning.getPostes()) {
            if (poste.getStand() == null
                    || poste.getCreneau() == null
                    || !jour.equals(poste.getCreneau().getDate())) {
                continue;
            }
            Cell cell = Cell.of(poste);
            cells.putIfAbsent(cell.key(), cell);
            List<String> ids = holders.computeIfAbsent(cell.key(), unused -> new ArrayList<>());
            if (poste.getAnimateur() != null) {
                ids.add(poste.getAnimateur().getId());
            }
        }
        holders.values().forEach(ids -> ids.sort(Comparator.naturalOrder()));
        return holders;
    }

    private static Holder holder(String animateurId, Map<String, Animateur> animateurs) {
        Animateur animateur = animateurs.get(animateurId);
        return new Holder(animateurId, animateur == null ? animateurId : animateur.nomAffiche());
    }

    /* -------------------------- Person by person -------------------------- */

    /**
     * The publication's own comparison, kept to the lines dated {@code jour}.
     * A déplacement pairs two shifts of the same day, so filtering on the new
     * shift's date keeps the pair whole.
     */
    private static List<AnimateurLine> animateurLines(
            LocalDate jour,
            PlanningEvenement avant,
            PlanningEvenement courant,
            Map<String, Animateur> animateurs,
            PublicationDiffService diff) {
        Map<String, Identite> identites = new LinkedHashMap<>();
        for (Animateur animateur : animateurs.values()) {
            identites.put(animateur.getId(), new Identite(animateur.nomAffiche(), animateur.getEmail()));
        }
        List<ChangementAnimateur> changements = diff.comparer(
                PublicationDiffService.vacationsByAnimateur(avant),
                PublicationDiffService.vacationsByAnimateur(courant),
                identites,
                false);
        List<AnimateurLine> lignes = new ArrayList<>();
        for (ChangementAnimateur changement : changements) {
            List<AnimateurChange> duJour = changement.changements().stream()
                    .filter(vacation -> jour.equals(vacation.vacation().date()))
                    .map(vacation -> new AnimateurChange(vacation.type(), vacation.libelle()))
                    .toList();
            if (!duJour.isEmpty()) {
                lignes.add(new AnimateurLine(changement.animateurId(), changement.nomAffiche(), duJour));
            }
        }
        return List.copyOf(lignes);
    }

    private static Map<String, Animateur> animateursById(PlanningEvenement planning) {
        Map<String, Animateur> byId = new LinkedHashMap<>();
        if (planning == null) {
            return byId;
        }
        if (planning.getAnimateurs() != null) {
            for (Animateur animateur : planning.getAnimateurs()) {
                byId.putIfAbsent(animateur.getId(), animateur);
            }
        }
        if (planning.getPostes() != null) {
            for (PosteAffectation poste : planning.getPostes()) {
                if (poste.getAnimateur() != null) {
                    byId.putIfAbsent(poste.getAnimateur().getId(), poste.getAnimateur());
                }
            }
        }
        return byId;
    }
}
