package dev.sylvain.planning.service.edition;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.Emplacement;
import dev.sylvain.planning.domain.FenetreHoraire;
import dev.sylvain.planning.domain.HoraireStand;
import dev.sylvain.planning.domain.IndisponibiliteStand;
import dev.sylvain.planning.domain.JourneeType;
import dev.sylvain.planning.domain.ModeHoraire;
import dev.sylvain.planning.domain.NiveauCompetence;
import dev.sylvain.planning.domain.OuvertureStand;
import dev.sylvain.planning.domain.ParametresLegaux;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.domain.TypeContrainteAdHoc;
import dev.sylvain.planning.domain.TypeJoursHoraire;
import dev.sylvain.planning.domain.VacationType;
import dev.sylvain.planning.service.edition.EditionDelta.DeltaChange;
import dev.sylvain.planning.service.edition.EditionDelta.DeltaFamily;
import dev.sylvain.planning.service.edition.EditionDelta.DeltaFamilyCount;
import dev.sylvain.planning.service.edition.EditionDelta.DeltaLine;
import dev.sylvain.planning.service.edition.EditionDelta.DeltaMatch;
import dev.sylvain.planning.service.edition.EditionDelta.DeltaSide;
import dev.sylvain.planning.service.edition.EditionDelta.DeltaTimeslotLine;
import dev.sylvain.planning.service.edition.EditionDelta.DeltaValueGroup;
import dev.sylvain.planning.service.edition.EditionDelta.DeltaValueLine;
import dev.sylvain.planning.service.edition.EditionDelta.DeltaVolumes;
import dev.sylvain.planning.service.referentiel.TypologieItem;
import dev.sylvain.planning.solver.ConstraintCatalog;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The referential delta on two editions built in memory: what a duplication
 * then three edits leaves, what two editions entered apart are matched on,
 * the timeslots aligned on the opening day, and the {@code A151} trap — the
 * same id in two editions naming two different people.
 */
class EditionDeltaComparatorTest {

    private static final DeltaVolumes VOLUMES = new DeltaVolumes(2, 4, 16.0, 70.0, 16.0 / 70.0);

    /* ------------------------------ Builders ------------------------------ */

    /** A mutable edition, built field by field, then frozen into a {@link EditionDeltaComparator.Side}. */
    private static final class Edition {
        final String id;
        final List<TypologieItem> typologies = new ArrayList<>();
        final List<Emplacement> emplacements = new ArrayList<>();
        final List<Stand> stands = new ArrayList<>();
        final List<Animateur> animateurs = new ArrayList<>();
        final List<Creneau> creneaux = new ArrayList<>();
        final List<JourneeType> journeesTypes = new ArrayList<>();
        ParametresLegaux legaux = new ParametresLegaux();
        final Map<String, Boolean> etats = new HashMap<>();
        final Map<String, Integer> poids = new HashMap<>();
        final Map<TypeContrainteAdHoc, Integer> ajustements = new EnumMap<>(TypeContrainteAdHoc.class);
        DeltaVolumes volumes = VOLUMES;

        Edition(String id) {
            this.id = id;
        }

        EditionDeltaComparator.Side side() {
            return new EditionDeltaComparator.Side(
                    new DeltaSide(id, "Édition " + id),
                    typologies,
                    emplacements,
                    stands,
                    animateurs,
                    creneaux,
                    journeesTypes,
                    legaux,
                    etats,
                    poids,
                    ajustements,
                    volumes);
        }
    }

    private static Stand stand(String id, String code, String nom, int effectifMax, String... typologies) {
        Stand stand = new Stand(id, nom, new HashSet<>(Set.of(typologies)), 1, effectifMax, false);
        stand.setCode(code);
        return stand;
    }

    private static Animateur animateur(String id, String prenom, String nom, String email) {
        Animateur animateur = new Animateur(id, prenom, nom, LocalDate.of(1990, 5, 4), false);
        animateur.setEmail(email);
        return animateur;
    }

    private static Creneau creneau(long id, String date, int debut, int fin) {
        return new Creneau(id, 0, LocalDate.parse(date), LocalTime.of(debut, 0), LocalTime.of(fin, 0));
    }

    /** The edition « Année 2025 » the scenarios start from. */
    private static Edition base(String id) {
        Edition edition = new Edition(id);
        edition.typologies.add(new TypologieItem("T1", "JEUX", "Jeux de société", false, null, null, null));
        edition.typologies.add(new TypologieItem("T2", "Stratégie"));
        edition.emplacements.add(new Emplacement("L1", "Place centrale", 46.5, 0.3));
        Stand cirque = stand("S1", null, "Cirque", 2, "T1");
        cirque.setEmplacement(edition.emplacements.get(0));
        edition.stands.add(cirque);
        edition.stands.add(stand("S2", "ECH", "Échecs", 1, "T2"));
        Animateur alice = animateur("A1", "Alice", "Martin", "alice@example.org");
        alice.setCompetences(new HashMap<>(Map.of("T1", NiveauCompetence.AUTONOME)));
        alice.setSouhaits(new HashSet<>(Set.of("T2")));
        edition.animateurs.add(alice);
        edition.animateurs.add(animateur("A2", "Bob", "Durand", "bob@example.org"));
        edition.creneaux.add(creneau(1, "2025-07-12", 10, 14));
        edition.creneaux.add(creneau(2, "2025-07-12", 14, 18));
        edition.creneaux.add(creneau(3, "2025-07-13", 10, 14));
        edition.journeesTypes.add(new JourneeType(
                1L, "Jour normal", List.of(new VacationType(LocalTime.of(10, 0), LocalTime.of(14, 0), false))));
        edition.ajustements.put(TypeContrainteAdHoc.AFFINITE, 2);
        return edition;
    }

    private static int lineCount(EditionDelta delta) {
        return delta.typologies().size()
                + delta.emplacements().size()
                + delta.stands().size()
                + delta.animateurs().size()
                + delta.journeesTypes().size()
                + delta.creneaux().size()
                + delta.parametres().size()
                + delta.ajustements().size();
    }

    private static DeltaFamilyCount family(EditionDelta delta, DeltaFamily family) {
        return delta.summary().families().stream()
                .filter(count -> count.family() == family)
                .findFirst()
                .orElseThrow();
    }

    /* -------------------------------- Tests ------------------------------- */

    @Test
    void anEditionComparedWithItselfIsEmpty() {
        Edition edition = base("E1");

        EditionDelta delta = EditionDeltaComparator.compare(edition.side(), edition.side());

        assertThat(delta.noDifference()).isTrue();
        assertThat(delta.noAnimateurMatched()).isFalse();
        assertThat(delta.summary().families())
                .allSatisfy(count -> assertThat(count.added() + count.removed() + count.modified())
                        .isZero());
        assertThat(delta.summary().seatDifference()).isZero();
        assertThat(delta.summary().referenceDays()).isEqualTo(2);
        assertThat(delta.summary().targetDays()).isEqualTo(2);
    }

    @Test
    void aDuplicationThenThreeEditsListsExactlyThoseThree() {
        Edition a = base("E1");
        Edition b = base("E2");
        b.stands.add(stand("S3", null, "Buvette", 3, "T1"));
        b.stands.get(0).setEffectifMax(4);
        b.animateurs.remove(1);

        EditionDelta delta = EditionDeltaComparator.compare(a.side(), b.side());

        assertThat(lineCount(delta)).isEqualTo(3);
        assertThat(delta.stands())
                .containsExactlyInAnyOrder(
                        new DeltaLine(DeltaChange.ADDED, null, null, "S3", null, "Buvette", List.of()),
                        new DeltaLine(
                                DeltaChange.MODIFIED,
                                DeltaMatch.NOM,
                                "S1",
                                "S1",
                                null,
                                "Cirque",
                                List.of("effectifMax")));
        assertThat(delta.animateurs())
                .containsExactly(new DeltaLine(DeltaChange.REMOVED, null, "A2", null, null, "Bob Durand", List.of()));
        assertThat(family(delta, DeltaFamily.STAND).added()).isEqualTo(1);
        assertThat(family(delta, DeltaFamily.STAND).modified()).isEqualTo(1);
        assertThat(family(delta, DeltaFamily.ANIMATEUR).removed()).isEqualTo(1);
    }

    /**
     * The trap of ADR 0050 D1: the counter is copied by the duplication, so
     * the first animateur created after it is {@code A151} in both editions —
     * two different people. One gone, one arrived; never « A151 modified ».
     */
    @Test
    void theSameIdInTwoEditionsIsNotTheSamePerson() {
        Edition a = new Edition("E1");
        a.animateurs.add(animateur("A151", "Alice", "Martin", "alice@example.org"));
        Edition b = new Edition("E2");
        b.animateurs.add(animateur("A151", "Bruno", "Petit", "bruno@example.org"));

        EditionDelta delta = EditionDeltaComparator.compare(a.side(), b.side());

        assertThat(delta.animateurs())
                .extracting(DeltaLine::change, DeltaLine::referenceId, DeltaLine::targetId)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(DeltaChange.REMOVED, "A151", null),
                        org.assertj.core.groups.Tuple.tuple(DeltaChange.ADDED, null, "A151"));
        // The target has a team of its own: not a duplication without the people.
        assertThat(delta.noAnimateurMatched()).isFalse();
    }

    /**
     * A family address passed from one sibling to the other: the e-mail is
     * the same, the birth dates are not — two people, and each one still
     * finds itself by identity.
     */
    @Test
    void aSharedEmailWithTwoBirthDatesIsTwoPeople() {
        Edition a = new Edition("E1");
        Animateur aine = animateur("A1", "Léa", "Martin", "famille.martin@example.org");
        aine.setDateNaissance(LocalDate.of(2005, 1, 1));
        a.animateurs.add(aine);
        Edition b = new Edition("E2");
        Animateur cadet = animateur("A1", "Hugo", "Martin", "famille.martin@example.org");
        cadet.setDateNaissance(LocalDate.of(2009, 6, 6));
        b.animateurs.add(cadet);
        Animateur leaEncore = animateur("A2", "Léa", "Martin", "lea.martin@example.org");
        leaEncore.setDateNaissance(LocalDate.of(2005, 1, 1));
        b.animateurs.add(leaEncore);

        EditionDelta delta = EditionDeltaComparator.compare(a.side(), b.side());

        assertThat(delta.animateurs())
                .extracting(DeltaLine::change, DeltaLine::matching, DeltaLine::referenceId, DeltaLine::targetId)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(DeltaChange.MODIFIED, DeltaMatch.IDENTITE, "A1", "A2"),
                        org.assertj.core.groups.Tuple.tuple(DeltaChange.ADDED, null, null, "A1"));
    }

    @Test
    void aSharedEmailWithOneBirthDateMissingStillMatches() {
        Edition a = new Edition("E1");
        Animateur alice = animateur("A1", "Alice", "Martin", "alice@example.org");
        alice.setDateNaissance(null);
        a.animateurs.add(alice);
        Edition b = new Edition("E2");
        b.animateurs.add(animateur("A7", "Alice", "Martin", "alice@example.org"));

        EditionDelta delta = EditionDeltaComparator.compare(a.side(), b.side());

        assertThat(delta.animateurs())
                .singleElement()
                .extracting(DeltaLine::matching, DeltaLine::fields)
                .containsExactly(DeltaMatch.EMAIL, List.of("dateNaissance"));
    }

    @Test
    void accentsAreIgnoredByTheMatchingKeys() {
        Edition a = new Edition("E1");
        a.animateurs.add(animateur("A1", "Zoé", "Lefèvre", null));
        a.stands.add(stand("S1", null, "Échecs", 2));
        Edition b = new Edition("E2");
        b.animateurs.add(animateur("A5", "Zoe", "Lefevre", null));
        b.stands.add(stand("S4", null, "Echecs", 2));

        EditionDelta delta = EditionDeltaComparator.compare(a.side(), b.side());

        assertThat(delta.animateurs())
                .singleElement()
                .extracting(DeltaLine::change, DeltaLine::matching, DeltaLine::fields)
                .containsExactly(DeltaChange.MODIFIED, DeltaMatch.IDENTITE, List.of("prenom", "nom"));
        assertThat(delta.stands())
                .singleElement()
                .extracting(DeltaLine::change, DeltaLine::matching, DeltaLine::fields)
                .containsExactly(DeltaChange.MODIFIED, DeltaMatch.NOM, List.of("nom"));
    }

    @Test
    void animateursAreMatchedByEmailCaseIgnoredThenByIdentity() {
        Edition a = new Edition("E1");
        a.animateurs.add(animateur("A1", "Alice", "Martin", "Alice@Example.org"));
        a.animateurs.add(animateur("A2", "Bob", "Durand", null));
        Edition b = new Edition("E2");
        Animateur alice = animateur("A9", "Alice", "Martin-Roy", "alice@example.org");
        b.animateurs.add(alice);
        Animateur bob = animateur("A4", " bob ", "DURAND", "bob@example.org");
        bob.setManager(true);
        b.animateurs.add(bob);

        EditionDelta delta = EditionDeltaComparator.compare(a.side(), b.side());

        assertThat(delta.animateurs())
                .containsExactly(
                        new DeltaLine(
                                DeltaChange.MODIFIED,
                                DeltaMatch.EMAIL,
                                "A1",
                                "A9",
                                null,
                                "Alice Martin-Roy",
                                List.of("nom", "email")),
                        new DeltaLine(
                                DeltaChange.MODIFIED,
                                DeltaMatch.IDENTITE,
                                "A2",
                                "A4",
                                null,
                                "bob DURAND",
                                List.of("prenom", "nom", "email", "manager")));
        assertThat(delta.noAnimateurMatched()).isFalse();
    }

    @Test
    void homonymsWithoutAnythingToTellThemApartAreNotMatched() {
        Edition a = new Edition("E1");
        a.animateurs.add(animateur("A1", "Jean", "Dupont", null));
        a.animateurs.add(animateur("A2", "Jean", "Dupont", null));
        Edition b = new Edition("E2");
        b.animateurs.add(animateur("A1", "Jean", "Dupont", null));

        EditionDelta delta = EditionDeltaComparator.compare(a.side(), b.side());

        assertThat(delta.animateurs())
                .extracting(DeltaLine::change)
                .containsExactly(DeltaChange.REMOVED, DeltaChange.REMOVED, DeltaChange.ADDED);
    }

    @Test
    void aTargetWithItsOwnTeamIsNotADuplicationWithoutPeople() {
        Edition a = base("E1");
        Edition b = base("E2");
        b.animateurs.clear();
        b.animateurs.add(animateur("A9", "Chloé", "Nouvelle", "chloe@example.org"));

        EditionDelta delta = EditionDeltaComparator.compare(a.side(), b.side());

        assertThat(delta.noAnimateurMatched()).isFalse();
        assertThat(delta.animateurs()).hasSize(3);
    }

    @Test
    void anEditionDuplicatedWithoutItsPeopleSaysSoOnce() {
        Edition a = base("E1");
        Edition b = base("E2");
        b.animateurs.clear();

        EditionDelta delta = EditionDeltaComparator.compare(a.side(), b.side());

        assertThat(delta.noAnimateurMatched()).isTrue();
        assertThat(delta.animateurs()).hasSize(2).allMatch(line -> line.change() == DeltaChange.REMOVED);
    }

    /**
     * Two editions entered apart: different ids everywhere, the same stand
     * names. The stands are matched by name, the match is flagged, and the
     * game categories they offer are compared through the categories'
     * own matching — T1 here is T8 there.
     */
    @Test
    void editionsEnteredApartMatchStandsByNameAndFlagIt() {
        Edition a = base("E1");
        Edition b = new Edition("E2");
        b.typologies.add(new TypologieItem("T8", "JEUX", "Jeux de société", false, null, null, null));
        b.typologies.add(new TypologieItem("T9", "Stratégie"));
        b.emplacements.add(new Emplacement("L4", "place centrale", 46.5, 0.3));
        Stand cirque = stand("S10", null, "Cirque", 2, "T8");
        cirque.setEmplacement(b.emplacements.get(0));
        b.stands.add(cirque);
        b.stands.add(stand("S11", "ECH", "Échecs", 2, "T9"));

        EditionDelta delta = EditionDeltaComparator.compare(a.side(), b.side());

        assertThat(delta.typologies()).isEmpty();
        assertThat(delta.emplacements())
                .containsExactly(new DeltaLine(
                        DeltaChange.MODIFIED, DeltaMatch.NOM, "L1", "L4", null, "place centrale", List.of("nom")));
        assertThat(delta.stands())
                .containsExactly(new DeltaLine(
                        DeltaChange.MODIFIED, DeltaMatch.CODE, "S2", "S11", "ECH", "Échecs", List.of("effectifMax")));
        // The unchanged Cirque rests on its name all the same: counted, so the screen can say it.
        assertThat(family(delta, DeltaFamily.STAND).matchedByName()).isEqualTo(1);
        assertThat(family(delta, DeltaFamily.TYPOLOGIE).matchedByName()).isEqualTo(1);
    }

    @Test
    void twoDifferentCodesAreTwoDifferentStandsWhateverTheirName() {
        Edition a = new Edition("E1");
        a.stands.add(stand("S1", "CIRQ", "Cirque", 2));
        Edition b = new Edition("E2");
        b.stands.add(stand("S1", "CIRQ2", "Cirque", 2));

        EditionDelta delta = EditionDeltaComparator.compare(a.side(), b.side());

        assertThat(delta.stands())
                .extracting(DeltaLine::change)
                .containsExactly(DeltaChange.REMOVED, DeltaChange.ADDED);
    }

    @Test
    void aCategoryLeftUnmatchedChangesTheStandsAndPeopleCitingIt() {
        Edition a = base("E1");
        Edition b = base("E2");
        b.typologies.set(1, new TypologieItem("T2", "Rôle"));

        EditionDelta delta = EditionDeltaComparator.compare(a.side(), b.side());

        assertThat(delta.typologies())
                .extracting(DeltaLine::change, DeltaLine::label)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(DeltaChange.REMOVED, "Stratégie"),
                        org.assertj.core.groups.Tuple.tuple(DeltaChange.ADDED, "Rôle"));
        assertThat(delta.stands())
                .singleElement()
                .satisfies(line -> assertThat(line.fields()).containsExactly("typologiesProposees"));
        assertThat(delta.animateurs())
                .singleElement()
                .satisfies(line -> assertThat(line.fields()).containsExactly("souhaits"));
    }

    /**
     * Two editions never share their dates: the grid lines up on the rank of
     * the opening day — two weekends against four days in a row — then on
     * the start of the shift.
     */
    @Test
    void timeslotsAreComparedByPositionNotByDate() {
        Edition a = new Edition("E1");
        a.creneaux.add(creneau(1, "2025-07-05", 10, 14));
        a.creneaux.add(creneau(2, "2025-07-06", 10, 14));
        a.creneaux.add(creneau(3, "2025-07-12", 10, 14));
        a.creneaux.add(creneau(4, "2025-07-13", 10, 14));
        a.creneaux.add(creneau(5, "2025-07-13", 14, 18));
        Edition b = new Edition("E2");
        b.creneaux.add(creneau(11, "2026-07-09", 10, 14));
        b.creneaux.add(creneau(12, "2026-07-10", 10, 14));
        b.creneaux.add(creneau(13, "2026-07-11", 10, 14));
        b.creneaux.add(creneau(14, "2026-07-11", 18, 22));
        b.creneaux.add(creneau(15, "2026-07-12", 10, 14));
        b.creneaux.add(creneau(16, "2026-07-12", 14, 19));
        b.creneaux.add(creneau(17, "2026-07-13", 9, 12));

        EditionDelta delta = EditionDeltaComparator.compare(a.side(), b.side());

        assertThat(delta.creneaux())
                .containsExactly(
                        new DeltaTimeslotLine(
                                DeltaChange.ADDED,
                                null,
                                3,
                                LocalTime.of(18, 0),
                                LocalDate.parse("2025-07-12"),
                                LocalDate.parse("2026-07-11"),
                                null,
                                "14",
                                List.of()),
                        new DeltaTimeslotLine(
                                DeltaChange.MODIFIED,
                                DeltaMatch.POSITION,
                                4,
                                LocalTime.of(14, 0),
                                LocalDate.parse("2025-07-13"),
                                LocalDate.parse("2026-07-12"),
                                "5",
                                "16",
                                List.of("heureFin")),
                        new DeltaTimeslotLine(
                                DeltaChange.ADDED,
                                null,
                                5,
                                null,
                                null,
                                LocalDate.parse("2026-07-13"),
                                null,
                                null,
                                List.of()));
        assertThat(delta.summary().referenceDays()).isEqualTo(4);
        assertThat(delta.summary().targetDays()).isEqualTo(5);
    }

    /**
     * A stand shut on the second opening day of both editions — the 13 July
     * here, the 11 July there — has not changed: its dated windows and rules
     * compare by position, as the grid does. Shut on another day, it has.
     */
    @Test
    void standDatedWindowsCompareByOpeningDayNotByDate() {
        Edition a = new Edition("E1");
        a.creneaux.add(creneau(1, "2025-07-12", 10, 14));
        a.creneaux.add(creneau(2, "2025-07-13", 10, 14));
        a.creneaux.add(creneau(3, "2025-07-19", 10, 14));
        a.stands.add(windowed("S1", "2025-07-13", "2025-07-19", "2025-07-14"));
        Edition b = new Edition("E2");
        b.creneaux.add(creneau(11, "2026-07-10", 10, 14));
        b.creneaux.add(creneau(12, "2026-07-11", 10, 14));
        b.creneaux.add(creneau(13, "2026-07-17", 10, 14));
        b.stands.add(windowed("S7", "2026-07-11", "2026-07-17", "2026-07-12"));

        assertThat(EditionDeltaComparator.compare(a.side(), b.side()).stands()).isEmpty();

        b.stands.get(0).getIndisponibilites().get(0).setDate(LocalDate.parse("2026-07-10"));

        assertThat(EditionDeltaComparator.compare(a.side(), b.side()).stands())
                .singleElement()
                .extracting(DeltaLine::fields)
                .isEqualTo(List.of("indisponibilites"));
    }

    /** A stand shut on {@code fermeture}, open on {@code ouverture}, and a rule naming both and {@code horsGrille}. */
    private static Stand windowed(String id, String fermeture, String ouverture, String horsGrille) {
        Stand stand = stand(id, "BUV", "Buvette", 2);
        stand.setIndisponibilites(new ArrayList<>(List.of(new IndisponibiliteStand(
                null, LocalDate.parse(fermeture), LocalTime.of(10, 0), LocalTime.of(12, 0), null))));
        stand.setOuvertures(new ArrayList<>(List.of(new OuvertureStand(
                null, LocalDate.parse(ouverture), LocalTime.of(18, 0), LocalTime.of(22, 0), null, 3))));
        HoraireStand dates = new HoraireStand(
                null,
                ModeHoraire.FERMETURE,
                TypeJoursHoraire.DATES,
                List.of(new FenetreHoraire(LocalTime.of(12, 0), LocalTime.of(13, 0))));
        dates.setDates(Set.of(LocalDate.parse(fermeture), LocalDate.parse(horsGrille)));
        HoraireStand plage = new HoraireStand(
                null,
                ModeHoraire.OUVERTURE,
                TypeJoursHoraire.PLAGE,
                List.of(new FenetreHoraire(LocalTime.of(9, 0), LocalTime.of(20, 0))));
        plage.setDateDebut(LocalDate.parse(fermeture));
        plage.setDateFin(LocalDate.parse(ouverture));
        stand.setHoraires(new ArrayList<>(List.of(dates, plage)));
        return stand;
    }

    @Test
    void settingsAndAdjustmentsAreListedWhereTheyDiffer() {
        Edition a = base("E1");
        Edition b = base("E2");
        b.legaux = new ParametresLegaux(40 * 60);
        b.etats.put("reposQuotidienMinimal", false);
        b.poids.put("equilibrerCharge", 25);
        b.ajustements.put(TypeContrainteAdHoc.AFFINITE, 5);
        b.ajustements.put(TypeContrainteAdHoc.INCOMPATIBILITE, 1);

        EditionDelta delta = EditionDeltaComparator.compare(a.side(), b.side());

        assertThat(delta.parametres())
                .extracting(
                        DeltaValueLine::group,
                        DeltaValueLine::key,
                        DeltaValueLine::referenceValue,
                        DeltaValueLine::targetValue)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                DeltaValueGroup.LEGAL,
                                "dureeHebdomadaireMaxMinutes",
                                String.valueOf(new ParametresLegaux().getDureeHebdomadaireMaxMinutes()),
                                "2400"),
                        org.assertj.core.groups.Tuple.tuple(
                                DeltaValueGroup.CONSTRAINT_ACTIVE, "reposQuotidienMinimal", "true", "false"),
                        org.assertj.core.groups.Tuple.tuple(
                                DeltaValueGroup.CONSTRAINT_WEIGHT,
                                "equilibrerCharge",
                                String.valueOf(ConstraintCatalog.defaultWeight("equilibrerCharge")),
                                "25"));
        assertThat(delta.ajustements())
                .extracting(DeltaValueLine::key, DeltaValueLine::referenceValue, DeltaValueLine::targetValue)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("INCOMPATIBILITE", "0", "1"),
                        org.assertj.core.groups.Tuple.tuple("AFFINITE", "2", "5"));
    }

    /**
     * Compared as the next solve reads them: a weight written equal to its
     * default, or a switch written as the catalogue ships it, is no row at
     * all — and the default weight is the deployment's, not the catalogue's.
     */
    @Test
    void constraintSettingsCompareTheirEffectiveValues() {
        Edition a = base("E1");
        Edition b = base("E2");
        a.poids.put("equilibrerCharge", ConstraintCatalog.defaultWeight("equilibrerCharge"));
        a.etats.put("mineurNecessiteEncadrementMajeur", false);
        a.etats.put("reposQuotidienMinimal", true);

        assertThat(EditionDeltaComparator.compare(a.side(), b.side()).parametres())
                .isEmpty();

        a.poids.put("equilibrerCharge", 8);

        assertThat(EditionDeltaComparator.compare(a.side(), b.side(), nom -> 8).parametres())
                .isEmpty();
        assertThat(EditionDeltaComparator.compare(a.side(), b.side()).parametres())
                .extracting(DeltaValueLine::key, DeltaValueLine::referenceValue, DeltaValueLine::targetValue)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(
                        "equilibrerCharge", "8", String.valueOf(ConstraintCatalog.defaultWeight("equilibrerCharge"))));
    }

    @Test
    void dayTemplatesAreMatchedByName() {
        Edition a = base("E1");
        Edition b = base("E2");
        b.journeesTypes.set(
                0,
                new JourneeType(
                        7L,
                        "jour normal",
                        List.of(
                                new VacationType(LocalTime.of(10, 0), LocalTime.of(14, 0), false),
                                new VacationType(LocalTime.of(14, 0), LocalTime.of(18, 0), false))));

        EditionDelta delta = EditionDeltaComparator.compare(a.side(), b.side());

        assertThat(delta.journeesTypes())
                .containsExactly(new DeltaLine(
                        DeltaChange.MODIFIED,
                        DeltaMatch.NOM,
                        "1",
                        "7",
                        null,
                        "jour normal",
                        List.of("nom", "vacations")));
        // Their name is their only key: nothing to flag.
        assertThat(family(delta, DeltaFamily.JOURNEE_TYPE).matchedByName()).isZero();
    }

    @Test
    void theVolumesAreSideBySideAndTheirDifferenceSummed() {
        Edition a = base("E1");
        Edition b = base("E2");
        b.volumes = new DeltaVolumes(2, 10, 426.0, 70.0, EditionDeltaComparator.fillRatio(426.0, 70.0));

        EditionDelta delta = EditionDeltaComparator.compare(a.side(), b.side());

        assertThat(delta.summary().seatDifference()).isEqualTo(6);
        assertThat(delta.summary().hoursToFillDifference()).isEqualTo(410.0);
        assertThat(delta.referenceVolumes()).isEqualTo(VOLUMES);
        assertThat(delta.targetVolumes().fillRatio()).isEqualTo(426.0 / 70.0);
        assertThat(EditionDeltaComparator.fillRatio(10, 0)).isNull();
    }

    @Test
    void withoutPersonNamesDropsTheAnimateurLabelsOnly() {
        Edition a = base("E1");
        Edition b = base("E2");
        b.animateurs.remove(1);
        b.stands.add(stand("S3", null, "Buvette", 3, "T1"));

        EditionDelta delta = EditionDeltaComparator.compare(a.side(), b.side()).withoutPersonNames();

        assertThat(delta.animateurs()).singleElement().satisfies(line -> {
            assertThat(line.label()).isNull();
            assertThat(line.referenceId()).isEqualTo("A2");
        });
        assertThat(delta.stands()).singleElement().extracting(DeltaLine::label).isEqualTo("Buvette");
    }
}
