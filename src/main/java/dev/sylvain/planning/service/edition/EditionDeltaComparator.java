package dev.sylvain.planning.service.edition;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.Emplacement;
import dev.sylvain.planning.domain.HoraireStand;
import dev.sylvain.planning.domain.IndisponibiliteStand;
import dev.sylvain.planning.domain.JourneeType;
import dev.sylvain.planning.domain.NiveauCompetence;
import dev.sylvain.planning.domain.OuvertureStand;
import dev.sylvain.planning.domain.ParametresLegaux;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.domain.TypeContrainteAdHoc;
import dev.sylvain.planning.service.edition.EditionDelta.DeltaChange;
import dev.sylvain.planning.service.edition.EditionDelta.DeltaFamily;
import dev.sylvain.planning.service.edition.EditionDelta.DeltaFamilyCount;
import dev.sylvain.planning.service.edition.EditionDelta.DeltaLine;
import dev.sylvain.planning.service.edition.EditionDelta.DeltaMatch;
import dev.sylvain.planning.service.edition.EditionDelta.DeltaSide;
import dev.sylvain.planning.service.edition.EditionDelta.DeltaSummary;
import dev.sylvain.planning.service.edition.EditionDelta.DeltaTimeslotLine;
import dev.sylvain.planning.service.edition.EditionDelta.DeltaValueGroup;
import dev.sylvain.planning.service.edition.EditionDelta.DeltaValueLine;
import dev.sylvain.planning.service.edition.EditionDelta.DeltaVolumes;
import dev.sylvain.planning.service.journal.ChampsModifies;
import dev.sylvain.planning.service.referentiel.TypologieItem;
import dev.sylvain.planning.solver.ConstraintCatalog;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.Month;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The delta of {@link EditionDelta}, computed on two referentials already
 * loaded — pure and static, so every rule below is unit-tested on two sets of
 * objects, without a database.
 *
 * <p><b>Nothing is matched by id.</b> Two editions number their rows from the
 * same counter (the duplication copies it), so the first animateur created
 * after a duplication is {@code A151} in both, and they are two different
 * people (ADR 0050, D1). Matching on the id would report « A151 modified:
 * nom, competences » instead of one gone and one arrived. The keys, in the
 * order they are tried, each pairing only a row that is <b>alone</b> on both
 * sides to carry its value — an ambiguous key matches nothing. Every text key
 * is compared trimmed, case and accents ignored:</p>
 * <ul>
 *   <li>stands, emplacements, game categories: the readable code (ADR 0050,
 *       D2), then the name — unless the two rows carry two different codes,
 *       which says they are two different things. A match by name is flagged
 *       on its line;</li>
 *   <li>animateurs: the e-mail, case ignored — unless the two fiches carry
 *       two different birth dates, a family address passed from one sibling
 *       to the other —, then first name, last name and birth date together;
 *       anything else is not matched;</li>
 *   <li>day templates: the name, their only key — not a fallback, so not
 *       counted as one;</li>
 *   <li>timeslots: the rank of the opening day — the N-th date carrying a
 *       timeslot, so two weekends line up with four days in a row the same
 *       way the organiser counts them — then the start of the shift.</li>
 * </ul>
 *
 * <p>Fields are compared through {@link ChampsModifies}, the same comparison
 * the history uses, and reported by name only. The ids a row cites — the
 * game categories of a stand, its location, the competences and wishes of an
 * animateur — are first translated through the matching above: the same
 * category may be {@code T3} here and {@code T7} there. The dates a stand's
 * dated windows and rules name are translated the same way, into their
 * position in the event (the rank of the opening day, as for the grid): a
 * stand shut on the second day of both editions has not changed.</p>
 *
 * <p>Constraint switches and weights are compared as the next solve of each
 * edition would read them — its own value, else the default — never as
 * stored rows.</p>
 */
public final class EditionDeltaComparator {

    private EditionDeltaComparator() {}

    /**
     * One edition's referential, as much of it as the delta reads.
     *
     * @param ajustements the ad hoc constraints, counted by type
     * @param volumes     the volumetry of the edition
     */
    public record Side(
            DeltaSide edition,
            List<TypologieItem> typologies,
            List<Emplacement> emplacements,
            List<Stand> stands,
            List<Animateur> animateurs,
            List<Creneau> creneaux,
            List<JourneeType> journeesTypes,
            ParametresLegaux parametresLegaux,
            Map<String, Boolean> etatsContraintes,
            Map<String, Integer> poidsContraintes,
            Map<TypeContrainteAdHoc, Integer> ajustements,
            DeltaVolumes volumes) {}

    /** Animateur fields left out: dates are the edition's own, ninja is derived from the competences. */
    private static final Set<String> ANIMATEUR_FIELDS_IGNORED = Set.of("joursIndisponibles", "ninja");

    /** The date is what tells two editions apart, and what the position replaces. */
    private static final Set<String> CRENEAU_FIELDS_IGNORED = Set.of("date", "heureDebut");

    /** Prefix that makes an unmatched id differ from every id of the other edition. */
    private static final String UNMATCHED = "\u0000";

    /** The delta, constraint weights left unset defaulting to the catalogue's. */
    public static EditionDelta compare(Side reference, Side target) {
        return compare(reference, target, ConstraintCatalog::defaultWeight);
    }

    /**
     * @param defaultWeight the weight of a constraint neither edition set —
     *                      the deployment's, the same for both sides
     */
    public static EditionDelta compare(Side reference, Side target, ToIntFunction<String> defaultWeight) {
        Matching<TypologieItem> typologies = match(
                reference.typologies(),
                target.typologies(),
                List.of(byCode(TypologieItem::code), byName(TypologieItem::label, TypologieItem::code)));
        Matching<Emplacement> emplacements = match(
                reference.emplacements(),
                target.emplacements(),
                List.of(byCode(Emplacement::getCode), byName(Emplacement::getNom, Emplacement::getCode)));
        Matching<Stand> stands = match(
                reference.stands(),
                target.stands(),
                List.of(byCode(Stand::getCode), byName(Stand::getNom, Stand::getCode)));
        Matching<Animateur> animateurs = match(
                reference.animateurs(),
                target.animateurs(),
                List.of(
                        new Criterion<>(
                                DeltaMatch.EMAIL,
                                animateur -> normalise(animateur.getEmail()),
                                EditionDeltaComparator::sameBirthDateIfBothKnown),
                        new Criterion<>(DeltaMatch.IDENTITE, EditionDeltaComparator::identity, null)));
        Matching<JourneeType> journeesTypes = match(
                reference.journeesTypes(),
                target.journeesTypes(),
                List.of(new Criterion<>(DeltaMatch.NOM, journee -> normalise(journee.getNom()), null)));

        Map<String, String> typologieIds = targetToReference(typologies, TypologieItem::id);
        Map<String, String> emplacementIds = targetToReference(emplacements, Emplacement::getId);
        UnaryOperator<LocalDate> referencePosition = dayPosition(reference.creneaux());
        UnaryOperator<LocalDate> targetPosition = dayPosition(target.creneaux());

        List<DeltaLine> typologieLines = lines(
                typologies,
                TypologieItem::id,
                TypologieItem::code,
                TypologieItem::label,
                EditionDeltaComparator::typologieFields);
        List<DeltaLine> emplacementLines = lines(
                emplacements,
                Emplacement::getId,
                Emplacement::getCode,
                Emplacement::getNom,
                EditionDeltaComparator::emplacementFields);
        List<DeltaLine> standLines = lines(
                stands,
                Stand::getId,
                Stand::getCode,
                Stand::getNom,
                (avant, apres) -> ChampsModifies.surStand(
                        positioned(avant, referencePosition),
                        translated(positioned(apres, targetPosition), typologieIds, emplacementIds)));
        List<DeltaLine> animateurLines = lines(
                animateurs,
                Animateur::getId,
                animateur -> null,
                EditionDeltaComparator::displayName,
                (avant, apres) -> ChampsModifies.surAnimateur(avant, translated(apres, typologieIds)).stream()
                        .filter(field -> !ANIMATEUR_FIELDS_IGNORED.contains(field))
                        .toList());
        List<DeltaLine> journeeTypeLines = lines(
                journeesTypes,
                journee -> journee.getId() == null ? null : String.valueOf(journee.getId()),
                journee -> null,
                JourneeType::getNom,
                EditionDeltaComparator::journeeTypeFields);
        List<DeltaTimeslotLine> creneauLines = creneaux(reference.creneaux(), target.creneaux());
        List<DeltaValueLine> parametreLines = parametres(reference, target, defaultWeight);
        List<DeltaValueLine> ajustementLines = ajustements(reference.ajustements(), target.ajustements());

        DeltaSummary summary = new DeltaSummary(
                List.of(
                        count(DeltaFamily.TYPOLOGIE, typologieLines, DeltaLine::change, typologies),
                        count(DeltaFamily.EMPLACEMENT, emplacementLines, DeltaLine::change, emplacements),
                        count(DeltaFamily.STAND, standLines, DeltaLine::change, stands),
                        count(DeltaFamily.ANIMATEUR, animateurLines, DeltaLine::change, animateurs),
                        // A day template has no code: its name is its key, not a fallback to flag.
                        count(DeltaFamily.JOURNEE_TYPE, journeeTypeLines, DeltaLine::change, null),
                        count(DeltaFamily.CRENEAU, creneauLines, DeltaTimeslotLine::change, null),
                        new DeltaFamilyCount(DeltaFamily.PARAMETRE, 0, 0, parametreLines.size(), 0),
                        new DeltaFamilyCount(DeltaFamily.AJUSTEMENT, 0, 0, ajustementLines.size(), 0)),
                openingDays(reference.creneaux()).size(),
                openingDays(target.creneaux()).size(),
                target.volumes().posteCount() - reference.volumes().posteCount(),
                target.volumes().hoursToFill() - reference.volumes().hoursToFill());

        return new EditionDelta(
                reference.edition(),
                target.edition(),
                summary,
                !reference.animateurs().isEmpty() && target.animateurs().isEmpty(),
                typologieLines,
                emplacementLines,
                standLines,
                animateurLines,
                journeeTypeLines,
                creneauLines,
                parametreLines,
                ajustementLines,
                reference.volumes(),
                target.volumes());
    }

    /** Hours to fill over hours available, {@code null} when nobody is available. */
    public static Double fillRatio(double hoursToFill, double hoursAvailable) {
        return hoursAvailable > 0 ? hoursToFill / hoursAvailable : null;
    }

    /* ------------------------------ Matching ------------------------------ */

    /**
     * One key rows are matched on.
     *
     * @param key        the normalised value, {@code null} when the row has none (never matched on it)
     * @param compatible what two rows sharing the key must also satisfy; {@code null} for nothing more
     */
    record Criterion<T>(DeltaMatch kind, Function<T, String> key, BiPredicate<T, T> compatible) {}

    record Pair<T>(T reference, T target, DeltaMatch kind) {}

    /**
     * @param pairs   matched rows, in the reference's order
     * @param removed rows of the reference matched to nothing
     * @param added   rows of the target matched to nothing
     */
    record Matching<T>(List<Pair<T>> pairs, List<T> removed, List<T> added) {}

    private static <T> Criterion<T> byCode(Function<T, String> code) {
        return new Criterion<>(DeltaMatch.CODE, row -> normalise(code.apply(row)), null);
    }

    /** The name, unless both rows carry a code and the codes differ: two codes say two things. */
    private static <T> Criterion<T> byName(Function<T, String> name, Function<T, String> code) {
        return new Criterion<>(DeltaMatch.NOM, row -> normalise(name.apply(row)), (a, b) -> {
            String codeA = normalise(code.apply(a));
            String codeB = normalise(code.apply(b));
            return codeA == null || codeB == null || codeA.equals(codeB);
        });
    }

    /**
     * Each criterion in turn, over the rows still unmatched. A key pairs two
     * rows only when exactly one row of each side carries it: two homonyms
     * are left apart rather than paired by chance.
     */
    static <T> Matching<T> match(List<T> reference, List<T> target, List<Criterion<T>> criteria) {
        // By identity: a record compares by value, and a matching pairs rows, not values.
        Map<T, Pair<T>> parReference = new IdentityHashMap<>();
        Set<T> targetPaired = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Criterion<T> criterion : criteria) {
            Map<String, List<T>> referenceByKey = byKey(reference, criterion, row -> !parReference.containsKey(row));
            Map<String, List<T>> targetByKey = byKey(target, criterion, row -> !targetPaired.contains(row));
            referenceByKey.forEach((key, referenceRows) -> {
                List<T> targetRows = targetByKey.get(key);
                if (referenceRows.size() != 1 || targetRows == null || targetRows.size() != 1) {
                    return;
                }
                T a = referenceRows.get(0);
                T b = targetRows.get(0);
                if (criterion.compatible() == null || criterion.compatible().test(a, b)) {
                    parReference.put(a, new Pair<>(a, b, criterion.kind()));
                    targetPaired.add(b);
                }
            });
        }
        List<Pair<T>> pairs = new ArrayList<>();
        List<T> removed = new ArrayList<>();
        for (T row : reference) {
            Pair<T> pair = parReference.get(row);
            if (pair != null) {
                pairs.add(pair);
            } else {
                removed.add(row);
            }
        }
        List<T> added =
                target.stream().filter(row -> !targetPaired.contains(row)).toList();
        return new Matching<>(pairs, removed, added);
    }

    private static <T> Map<String, List<T>> byKey(List<T> rows, Criterion<T> criterion, Predicate<T> unmatched) {
        Map<String, List<T>> byKey = new LinkedHashMap<>();
        for (T row : rows) {
            String key = unmatched.test(row) ? criterion.key().apply(row) : null;
            if (key != null) {
                byKey.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
            }
        }
        return byKey;
    }

    /**
     * Trimmed, inner blanks collapsed, case and accents ignored — « Zoé » and
     * « Zoe » are the same key, the field comparison still reports the
     * spelling that moved; {@code null} for a blank value.
     */
    static String normalise(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String sansAccents = DIACRITICS
                .matcher(Normalizer.normalize(value.trim(), Normalizer.Form.NFD))
                .replaceAll("");
        return sansAccents.replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static final Pattern DIACRITICS = Pattern.compile("\\p{M}+");

    /**
     * Two fiches sharing an e-mail are one person unless both carry a birth
     * date and the dates differ: a family address handed from one sibling to
     * the other names two people, who then fall through to the identity.
     */
    private static boolean sameBirthDateIfBothKnown(Animateur a, Animateur b) {
        return a.getDateNaissance() == null
                || b.getDateNaissance() == null
                || a.getDateNaissance().equals(b.getDateNaissance());
    }

    /** First name, last name and birth date together, or nothing when one is missing. */
    private static String identity(Animateur animateur) {
        String prenom = normalise(animateur.getPrenom());
        String nom = normalise(animateur.getNom());
        if (prenom == null || nom == null || animateur.getDateNaissance() == null) {
            return null;
        }
        return prenom + "|" + nom + "|" + animateur.getDateNaissance();
    }

    private static <T> Map<String, String> targetToReference(Matching<T> matching, Function<T, String> id) {
        Map<String, String> ids = new HashMap<>();
        for (Pair<T> pair : matching.pairs()) {
            ids.put(id.apply(pair.target()), id.apply(pair.reference()));
        }
        return ids;
    }

    /** A target id in the reference's numbering; one matched to nothing equals no reference id. */
    private static String translate(String targetId, Map<String, String> ids) {
        if (targetId == null) {
            return null;
        }
        String referenceId = ids.get(targetId);
        return referenceId != null ? referenceId : UNMATCHED + targetId;
    }

    /* ------------------------------- Lines -------------------------------- */

    private static <T> List<DeltaLine> lines(
            Matching<T> matching,
            Function<T, String> id,
            Function<T, String> code,
            Function<T, String> label,
            FieldComparison<T> fields) {
        List<DeltaLine> lines = new ArrayList<>(matching.removed().stream()
                .map(row -> new DeltaLine(
                        DeltaChange.REMOVED, null, id.apply(row), null, code.apply(row), label.apply(row), List.of()))
                .toList());
        lines.addAll(matching.added().stream()
                .map(row -> new DeltaLine(
                        DeltaChange.ADDED, null, null, id.apply(row), code.apply(row), label.apply(row), List.of()))
                .toList());
        for (Pair<T> pair : matching.pairs()) {
            List<String> changed = fields.apply(pair.reference(), pair.target());
            // A code given on one side only is a difference the fields do not see.
            boolean codeChanged =
                    !Objects.equals(normalise(code.apply(pair.reference())), normalise(code.apply(pair.target())));
            List<String> all = new ArrayList<>();
            if (codeChanged) {
                all.add("code");
            }
            all.addAll(changed);
            if (!all.isEmpty()) {
                String targetCode = code.apply(pair.target());
                lines.add(new DeltaLine(
                        DeltaChange.MODIFIED,
                        pair.kind(),
                        id.apply(pair.reference()),
                        id.apply(pair.target()),
                        targetCode != null ? targetCode : code.apply(pair.reference()),
                        label.apply(pair.target()),
                        List.copyOf(all)));
            }
        }
        return lines;
    }

    @FunctionalInterface
    private interface FieldComparison<T> {
        List<String> apply(T reference, T target);
    }

    private static <L> DeltaFamilyCount count(
            DeltaFamily family, List<L> lines, Function<L, DeltaChange> change, Matching<?> matching) {
        Map<DeltaChange, Long> counts = lines.stream()
                .collect(Collectors.groupingBy(change, () -> new EnumMap<>(DeltaChange.class), Collectors.counting()));
        int matchedByName = matching == null
                ? 0
                : (int) matching.pairs().stream()
                        .filter(pair -> pair.kind() == DeltaMatch.NOM)
                        .count();
        return new DeltaFamilyCount(
                family,
                counts.getOrDefault(DeltaChange.ADDED, 0L).intValue(),
                counts.getOrDefault(DeltaChange.REMOVED, 0L).intValue(),
                counts.getOrDefault(DeltaChange.MODIFIED, 0L).intValue(),
                matchedByName);
    }

    /* ------------------------------- Fields ------------------------------- */

    private static List<String> typologieFields(TypologieItem a, TypologieItem b) {
        List<String> fields = new ArrayList<>();
        if (!Objects.equals(a.label(), b.label())) {
            fields.add("label");
        }
        if (a.ninja() != b.ninja()) {
            fields.add("ninja");
        }
        if (!Objects.equals(a.maxCreneauxParAnimateur(), b.maxCreneauxParAnimateur())) {
            fields.add("maxCreneauxParAnimateur");
        }
        if (!Objects.equals(a.description(), b.description())) {
            fields.add("description");
        }
        return fields;
    }

    private static List<String> emplacementFields(Emplacement a, Emplacement b) {
        List<String> fields = new ArrayList<>();
        if (!Objects.equals(a.getNom(), b.getNom())) {
            fields.add("nom");
        }
        if (!Objects.equals(a.getLatitude(), b.getLatitude())) {
            fields.add("latitude");
        }
        if (!Objects.equals(a.getLongitude(), b.getLongitude())) {
            fields.add("longitude");
        }
        return fields;
    }

    private static List<String> journeeTypeFields(JourneeType a, JourneeType b) {
        List<String> fields = new ArrayList<>();
        if (!Objects.equals(a.getNom(), b.getNom())) {
            fields.add("nom");
        }
        if (!a.signature().equals(b.signature())) {
            fields.add("vacations");
        }
        return fields;
    }

    /** The target stand with the ids it cites in the reference's numbering — compared, never stored. */
    private static Stand translated(Stand stand, Map<String, String> typologieIds, Map<String, String> emplacementIds) {
        Stand copie = new Stand();
        copie.setId(stand.getId());
        copie.setCode(stand.getCode());
        copie.setNom(stand.getNom());
        copie.setTypologiesProposees(
                stand.getTypologiesProposees() == null
                        ? null
                        : stand.getTypologiesProposees().stream()
                                .map(id -> translate(id, typologieIds))
                                .collect(Collectors.toSet()));
        copie.setEffectifMin(stand.getEffectifMin());
        copie.setEffectifMax(stand.getEffectifMax());
        copie.setReserveMajeurs(stand.isReserveMajeurs());
        copie.setPremium(stand.isPremium());
        copie.setNiveauEffort(stand.getNiveauEffort());
        if (stand.getEmplacement() != null) {
            Emplacement emplacement = new Emplacement();
            emplacement.setId(translate(stand.getEmplacement().getId(), emplacementIds));
            copie.setEmplacement(emplacement);
        }
        copie.setIndisponibilites(stand.getIndisponibilites());
        copie.setOuvertures(stand.getOuvertures());
        copie.setHoraires(stand.getHoraires());
        return copie;
    }

    /**
     * The stand with every date its windows and rules name replaced by its
     * position in its own edition (see {@link #dayPosition}) — compared, never
     * stored. A stand shut on the second opening day of both editions is then
     * unchanged, whatever the calendar says; a weekday rule is left as is, the
     * weekday being what it names.
     */
    private static Stand positioned(Stand stand, UnaryOperator<LocalDate> position) {
        Stand copie = new Stand();
        copie.setId(stand.getId());
        copie.setCode(stand.getCode());
        copie.setNom(stand.getNom());
        copie.setTypologiesProposees(stand.getTypologiesProposees());
        copie.setEffectifMin(stand.getEffectifMin());
        copie.setEffectifMax(stand.getEffectifMax());
        copie.setReserveMajeurs(stand.isReserveMajeurs());
        copie.setPremium(stand.isPremium());
        copie.setNiveauEffort(stand.getNiveauEffort());
        copie.setEmplacement(stand.getEmplacement());
        copie.setIndisponibilites(stand.getIndisponibilites().stream()
                .map(fenetre -> new IndisponibiliteStand(
                        fenetre.getId(),
                        position.apply(fenetre.getDate()),
                        fenetre.getHeureDebut(),
                        fenetre.getHeureFin(),
                        fenetre.getMotif()))
                .collect(Collectors.toCollection(ArrayList::new)));
        copie.setOuvertures(stand.getOuvertures().stream()
                .map(fenetre -> new OuvertureStand(
                        fenetre.getId(),
                        position.apply(fenetre.getDate()),
                        fenetre.getHeureDebut(),
                        fenetre.getHeureFin(),
                        fenetre.getMotif(),
                        fenetre.getEffectif()))
                .collect(Collectors.toCollection(ArrayList::new)));
        copie.setHoraires(stand.getHoraires().stream()
                .map(horaire -> positioned(horaire, position))
                .collect(Collectors.toCollection(ArrayList::new)));
        return copie;
    }

    private static HoraireStand positioned(HoraireStand horaire, UnaryOperator<LocalDate> position) {
        HoraireStand copie =
                new HoraireStand(horaire.getId(), horaire.getMode(), horaire.getJours(), horaire.getFenetres());
        copie.setJoursSemaine(horaire.getJoursSemaine());
        copie.setDateDebut(position.apply(horaire.getDateDebut()));
        copie.setDateFin(position.apply(horaire.getDateFin()));
        copie.setDates(horaire.getDates().stream().map(position).collect(Collectors.toSet()));
        copie.setMotif(horaire.getMotif());
        return copie;
    }

    /** The target animateur with competences and wishes in the reference's numbering. */
    private static Animateur translated(Animateur animateur, Map<String, String> typologieIds) {
        Animateur copie = new Animateur(
                animateur.getId(),
                animateur.getPrenom(),
                animateur.getNom(),
                animateur.getDateNaissance(),
                animateur.isManager());
        copie.setEmail(animateur.getEmail());
        Map<String, NiveauCompetence> competences = new HashMap<>();
        animateur.getCompetences().forEach((id, niveau) -> competences.put(translate(id, typologieIds), niveau));
        copie.setCompetences(competences);
        copie.setSouhaits(animateur.getSouhaits().stream()
                .map(id -> translate(id, typologieIds))
                .collect(Collectors.toSet()));
        return copie;
    }

    /** First name then last name, as the screens write a person. */
    private static String displayName(Animateur animateur) {
        String prenom =
                animateur.getPrenom() == null ? "" : animateur.getPrenom().trim();
        String nom = animateur.getNom() == null ? "" : animateur.getNom().trim();
        String nomComplet = (prenom + " " + nom).trim();
        return nomComplet.isEmpty() ? null : nomComplet;
    }

    /* ------------------------------ Timeslots ----------------------------- */

    /** The dates carrying a timeslot, in order: the opening days, ranked from 1. */
    static List<LocalDate> openingDays(Collection<Creneau> creneaux) {
        return creneaux.stream()
                .map(Creneau::getDate)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(TreeSet::new))
                .stream()
                .toList();
    }

    /** Where the opening days of every edition are put: rank N is this date plus N days. */
    private static final LocalDate RANKED = LocalDate.of(1, Month.JANUARY, 1);

    /** Where the other dates are put: this date plus their distance to the first opening day. */
    private static final LocalDate OFF_GRID = RANKED.minusYears(1000);

    /**
     * A date of an edition as a position in its event, so two editions'
     * dated windows compare the way their grids do: an opening day becomes
     * its rank, any other date its distance to the first opening day — in
     * both cases a value two editions share when their events line up. An
     * edition without a timeslot has no position to give, and keeps its
     * dates.
     */
    static UnaryOperator<LocalDate> dayPosition(Collection<Creneau> creneaux) {
        List<LocalDate> jours = openingDays(creneaux);
        if (jours.isEmpty()) {
            return date -> date;
        }
        Map<LocalDate, Integer> rangs = new HashMap<>();
        for (int i = 0; i < jours.size(); i++) {
            rangs.put(jours.get(i), i + 1);
        }
        LocalDate premier = jours.get(0);
        return date -> {
            if (date == null) {
                return null;
            }
            Integer rang = rangs.get(date);
            return rang != null ? RANKED.plusDays(rang) : OFF_GRID.plusDays(ChronoUnit.DAYS.between(premier, date));
        };
    }

    private static List<DeltaTimeslotLine> creneaux(List<Creneau> reference, List<Creneau> target) {
        Map<LocalDate, List<Creneau>> parJourA = byDate(reference);
        Map<LocalDate, List<Creneau>> parJourB = byDate(target);
        List<LocalDate> joursA = new ArrayList<>(parJourA.keySet());
        List<LocalDate> joursB = new ArrayList<>(parJourB.keySet());
        List<DeltaTimeslotLine> lines = new ArrayList<>();
        for (int rang = 0; rang < Math.max(joursA.size(), joursB.size()); rang++) {
            int day = rang + 1;
            if (rang >= joursB.size()) {
                lines.add(new DeltaTimeslotLine(
                        DeltaChange.REMOVED, null, day, null, joursA.get(rang), null, null, null, List.of()));
            } else if (rang >= joursA.size()) {
                lines.add(new DeltaTimeslotLine(
                        DeltaChange.ADDED, null, day, null, null, joursB.get(rang), null, null, List.of()));
            } else {
                lines.addAll(sameDay(
                        day,
                        joursA.get(rang),
                        joursB.get(rang),
                        parJourA.get(joursA.get(rang)),
                        parJourB.get(joursB.get(rang))));
            }
        }
        return lines;
    }

    private static Map<LocalDate, List<Creneau>> byDate(List<Creneau> creneaux) {
        Comparator<Creneau> ordre = Comparator.comparing(
                        Creneau::getHeureDebut, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(Creneau::getHeureFin, Comparator.nullsFirst(Comparator.naturalOrder()));
        Map<LocalDate, List<Creneau>> parJour = new TreeMap<>();
        for (Creneau creneau : creneaux) {
            if (creneau.getDate() != null) {
                parJour.computeIfAbsent(creneau.getDate(), date -> new ArrayList<>())
                        .add(creneau);
            }
        }
        parJour.values().forEach(jour -> jour.sort(ordre));
        return parJour;
    }

    /** The shifts of one opening day, paired by start, then in order of end among shifts starting together. */
    private static List<DeltaTimeslotLine> sameDay(
            int day, LocalDate dateA, LocalDate dateB, List<Creneau> creneauxA, List<Creneau> creneauxB) {
        Map<LocalTime, List<Creneau>> parDebutA = byStart(creneauxA);
        Map<LocalTime, List<Creneau>> parDebutB = byStart(creneauxB);
        Set<LocalTime> debuts = new TreeSet<>(Comparator.nullsFirst(Comparator.naturalOrder()));
        debuts.addAll(parDebutA.keySet());
        debuts.addAll(parDebutB.keySet());
        List<DeltaTimeslotLine> lines = new ArrayList<>();
        for (LocalTime debut : debuts) {
            List<Creneau> a = parDebutA.getOrDefault(debut, List.of());
            List<Creneau> b = parDebutB.getOrDefault(debut, List.of());
            for (int i = 0; i < Math.max(a.size(), b.size()); i++) {
                Creneau ca = i < a.size() ? a.get(i) : null;
                Creneau cb = i < b.size() ? b.get(i) : null;
                positionLine(day, debut, dateA, dateB, ca, cb).ifPresent(lines::add);
            }
        }
        return lines;
    }

    /** The line of the {@code i}-th shift starting at {@code debut} on either side — none when both match. */
    private static Optional<DeltaTimeslotLine> positionLine(
            int day, LocalTime debut, LocalDate dateA, LocalDate dateB, Creneau ca, Creneau cb) {
        if (cb == null) {
            return Optional.of(new DeltaTimeslotLine(
                    DeltaChange.REMOVED, null, day, debut, dateA, dateB, id(ca), null, List.of()));
        }
        if (ca == null) {
            return Optional.of(
                    new DeltaTimeslotLine(DeltaChange.ADDED, null, day, debut, dateA, dateB, null, id(cb), List.of()));
        }
        List<String> fields = ChampsModifies.surCreneau(ca, cb).stream()
                .filter(field -> !CRENEAU_FIELDS_IGNORED.contains(field))
                .toList();
        if (fields.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new DeltaTimeslotLine(
                DeltaChange.MODIFIED, DeltaMatch.POSITION, day, debut, dateA, dateB, id(ca), id(cb), fields));
    }

    private static Map<LocalTime, List<Creneau>> byStart(List<Creneau> creneaux) {
        Map<LocalTime, List<Creneau>> parDebut = new HashMap<>();
        for (Creneau creneau : creneaux) {
            parDebut.computeIfAbsent(creneau.getHeureDebut(), debut -> new ArrayList<>())
                    .add(creneau);
        }
        return parDebut;
    }

    private static String id(Creneau creneau) {
        return creneau.getId() == null ? null : String.valueOf(creneau.getId());
    }

    /* ----------------------------- Parameters ----------------------------- */

    /** The legal parameters, in the order the Règles screen shows them. */
    private static final Map<String, Function<ParametresLegaux, Object>> LEGAUX = legaux();

    private static Map<String, Function<ParametresLegaux, Object>> legaux() {
        Map<String, Function<ParametresLegaux, Object>> map = new LinkedHashMap<>();
        map.put("dureeHebdomadaireMaxMinutes", ParametresLegaux::getDureeHebdomadaireMaxMinutes);
        map.put("dureeHebdomadaireMaxMineurMinutes", ParametresLegaux::getDureeHebdomadaireMaxMineurMinutes);
        map.put("dureeVacationMaxMinutes", ParametresLegaux::getDureeVacationMaxMinutes);
        map.put("reposQuotidienMinimalMinutes", ParametresLegaux::getReposQuotidienMinimalMinutes);
        map.put("dureePauseMinutes", ParametresLegaux::getDureePauseMinutes);
        map.put("coupureRepasMinutes", ParametresLegaux::getCoupureRepasMinutes);
        map.put("coupureRepasMidiDebut", ParametresLegaux::getCoupureRepasMidiDebut);
        map.put("coupureRepasMidiFin", ParametresLegaux::getCoupureRepasMidiFin);
        map.put("coupureRepasSoirDebut", ParametresLegaux::getCoupureRepasSoirDebut);
        map.put("coupureRepasSoirFin", ParametresLegaux::getCoupureRepasSoirFin);
        map.put("heureDebutSoiree", ParametresLegaux::getHeureDebutSoiree);
        return Collections.unmodifiableMap(map);
    }

    private static List<DeltaValueLine> parametres(Side reference, Side target, ToIntFunction<String> defaultWeight) {
        List<DeltaValueLine> lines = new ArrayList<>();
        ParametresLegaux legauxA = orDefault(reference.parametresLegaux());
        ParametresLegaux legauxB = orDefault(target.parametresLegaux());
        LEGAUX.forEach((cle, lecture) -> {
            String a = text(lecture.apply(legauxA));
            String b = text(lecture.apply(legauxB));
            if (!Objects.equals(a, b)) {
                lines.add(new DeltaValueLine(DeltaValueGroup.LEGAL, cle, null, a, b));
            }
        });
        // Effective values, not the stored overrides: a weight written equal
        // to its default and no row at all are the same solve.
        for (String nom : constraintNames(reference.etatsContraintes(), target.etatsContraintes())) {
            boolean a = effectiveActive(reference.etatsContraintes(), nom);
            boolean b = effectiveActive(target.etatsContraintes(), nom);
            if (a != b) {
                lines.add(new DeltaValueLine(
                        DeltaValueGroup.CONSTRAINT_ACTIVE, nom, constraintLabel(nom), text(a), text(b)));
            }
        }
        for (String nom : constraintNames(reference.poidsContraintes(), target.poidsContraintes())) {
            int a = effectiveWeight(reference.poidsContraintes(), nom, defaultWeight);
            int b = effectiveWeight(target.poidsContraintes(), nom, defaultWeight);
            if (a != b) {
                lines.add(new DeltaValueLine(
                        DeltaValueGroup.CONSTRAINT_WEIGHT, nom, constraintLabel(nom), text(a), text(b)));
            }
        }
        return lines;
    }

    /** What the next solve of that edition enforces: its own choice, else the catalogue's. */
    private static boolean effectiveActive(Map<String, Boolean> etats, String nom) {
        Boolean propre = map(etats).get(nom);
        return propre != null ? propre : ConstraintCatalog.activeByDefault(nom);
    }

    /** The weight the next solve of that edition gives: its own, else the deployment's. */
    private static int effectiveWeight(Map<String, Integer> poids, String nom, ToIntFunction<String> defaultWeight) {
        Integer propre = map(poids).get(nom);
        return propre != null ? propre : defaultWeight.applyAsInt(nom);
    }

    /** Every rule of the catalogue, and any stored name it no longer carries, sorted. */
    private static <V> Set<String> constraintNames(Map<String, V> a, Map<String, V> b) {
        Set<String> noms = new TreeSet<>();
        ConstraintCatalog.definitions().forEach(definition -> noms.add(definition.name()));
        noms.addAll(map(a).keySet());
        noms.addAll(map(b).keySet());
        return noms;
    }

    private static List<DeltaValueLine> ajustements(
            Map<TypeContrainteAdHoc, Integer> reference, Map<TypeContrainteAdHoc, Integer> target) {
        List<DeltaValueLine> lines = new ArrayList<>();
        for (TypeContrainteAdHoc type : TypeContrainteAdHoc.values()) {
            int a = map(reference).getOrDefault(type, 0);
            int b = map(target).getOrDefault(type, 0);
            if (a != b) {
                lines.add(new DeltaValueLine(
                        DeltaValueGroup.AJUSTEMENT, type.name(), null, String.valueOf(a), String.valueOf(b)));
            }
        }
        return lines;
    }

    private static ParametresLegaux orDefault(ParametresLegaux parametres) {
        return parametres == null ? new ParametresLegaux() : parametres;
    }

    private static String constraintLabel(String nom) {
        ConstraintCatalog.ConstraintDefinition definition = ConstraintCatalog.PAR_NOM.get(nom);
        return definition == null ? null : definition.libelleCourt();
    }

    private static <K, V> Map<K, V> map(Map<K, V> map) {
        return map == null ? Map.of() : map;
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
