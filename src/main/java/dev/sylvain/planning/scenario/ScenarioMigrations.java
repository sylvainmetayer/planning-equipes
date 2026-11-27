package dev.sylvain.planning.scenario;

import dev.sylvain.planning.solver.ConstraintCatalog;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * The chain that turns an older scenario document into the shape this build
 * reads, applied to the parsed map before binding (ADR 0101).
 *
 * <p>Each link is one pure function from version {@code n} to {@code n + 1},
 * with its own test: a model change that renames or reshapes a key adds its
 * link here, so a file written by any earlier release — a bundled scenario, an
 * end-of-event archive, a copy taken before a wipe — stays importable. What a
 * link cannot carry over is dropped <b>and said</b>, through {@code warnings}
 * (they reach the import report), never silently.</p>
 */
public final class ScenarioMigrations {

    /** One link of the chain: reads a document of version {@code from()} and returns it as {@code from() + 1}. */
    public record Link(int from, Conversion conversion) {}

    @FunctionalInterface
    public interface Conversion {
        /**
         * @param warnings receives one French sentence per thing that could not be converted, naming a key or an
         *     id, never a person: it reaches the import report and the browser's notification log
         */
        Map<String, Object> apply(Map<String, Object> document, List<String> warnings);
    }

    /** Ordered by {@code from}; the last link must end at {@link ScenarioBinder#SCHEMA_VERSION}. */
    static final List<Link> CHAIN = List.of(
            new Link(1, ScenarioMigrations::v1ToV2),
            new Link(2, ScenarioParametresLink::convert),
            new Link(3, ScenarioMigrations::v3ToV4),
            new Link(4, ScenarioPlagesLink::convert),
            new Link(5, ScenarioMigrations::v5ToV6),
            new Link(6, ScenarioMigrations::v6ToV7));

    private ScenarioMigrations() {}

    /**
     * The document as the current version, plus what was dropped on the way.
     * The input is not modified. A missing {@code schemaVersion} means 1.
     */
    public static Migrated migrate(Map<String, Object> document, int fromVersion) {
        List<String> warnings = new ArrayList<>();
        Map<String, Object> current = new LinkedHashMap<>(document);
        for (Link link : CHAIN) {
            if (link.from() >= fromVersion) {
                current = link.conversion().apply(current, warnings);
            }
        }
        current = withRetiredLockTypesConverted(current, warnings);
        current = withRetiredAdHocTypesConverted(current, warnings);
        current = foldHardRunForm(current, warnings);
        current = renameRetiredRules(current, warnings);
        current.put("schemaVersion", ScenarioBinder.SCHEMA_VERSION);
        return new Migrated(current, List.copyOf(warnings));
    }

    public record Migrated(Map<String, Object> document, List<String> warnings) {}

    /** What an entry of {@code niveaux:} is worth against another when two merged rules disagree: first wins. */
    private static final List<String> NIVEAUX_PAR_PROTECTION =
            List.of("OBLIGATOIRE", "PREFERENCE_FORTE", "PREFERENCE", "PREFERENCE_NORMALE", "PREFERENCE_LEGERE");

    /**
     * Not a version link: applied to every document, whatever its version, as 4.0
     * is not released and the builds of {@code main} wrote rule names of both
     * kinds under the same {@code schemaVersion}. Every rule key of
     * {@code contraintes:} naming a rule merged in 4.0 ({@link
     * ConstraintCatalog#ANCIENS_NOMS}) is read as the rule that replaced it, and
     * said once. Two old rules that became one are folded on the <b>most
     * protective</b> reading — the rule stays switched on if either was, takes
     * the strongest weight, and the hardest level — the same decision as
     * migrations V152 and V154 for an edition already stored. A rule that kept
     * its name ({@link ConstraintCatalog#NOMS_CONSERVES}) votes with the retired
     * one even when the file does not write it: it is then on.
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> renameRetiredRules(Map<String, Object> document, List<String> warnings) {
        if (!(document.get("contraintes") instanceof Map<?, ?> raw)) {
            return document;
        }
        Map<String, Object> contraintes = new LinkedHashMap<>((Map<String, Object>) raw);
        Set<String> successors = new LinkedHashSet<>();
        for (String key : List.of("desactivees", "activees")) {
            if (contraintes.get(key) instanceof List<?> names) {
                names.stream().map(String::valueOf).forEach(name -> noteRename(name, successors, warnings));
            }
        }
        for (String key : List.of("poids", "niveaux")) {
            if (contraintes.get(key) instanceof Map<?, ?> byName) {
                byName.keySet().stream().map(String::valueOf).forEach(name -> noteRename(name, successors, warnings));
            }
        }
        if (successors.isEmpty()) {
            return document;
        }
        List<String> disabled = new ArrayList<>(listOf(contraintes.get("desactivees")));
        List<String> enabled = new ArrayList<>(listOf(contraintes.get("activees")));
        for (String successor : successors) {
            // An old name not written is the rule's default, on: only the names the file gives vote.
            Set<String> group = new LinkedHashSet<>(ConstraintCatalog.formerNamesOf(successor));
            // A rule that kept its name (ConstraintCatalog.NOMS_CONSERVES) was one of the pair: not written,
            // it is on, and votes as such. A new name was nobody's, it votes only when the file writes it.
            if (ConstraintCatalog.NOMS_CONSERVES.contains(successor)
                    || disabled.contains(successor)
                    || enabled.contains(successor)) {
                group.add(successor);
            }
            boolean anyEnabled = group.stream().anyMatch(enabled::contains);
            boolean allDisabled = disabled.containsAll(group);
            disabled.removeAll(group);
            enabled.removeAll(group);
            if (anyEnabled) {
                enabled.add(successor);
            } else if (allDisabled) {
                disabled.add(successor);
            }
        }
        putIfPresent(contraintes, "desactivees", disabled);
        putIfPresent(contraintes, "activees", enabled);
        if (contraintes.get("poids") instanceof Map<?, ?> poids) {
            Map<String, Object> merged = new LinkedHashMap<>();
            poids.forEach((name, weight) -> {
                String current = ConstraintCatalog.currentName(String.valueOf(name));
                // Map.merge refuses a null value, and a bare `name:` in the YAML is one
                if (weight == null) {
                    merged.putIfAbsent(current, null);
                } else {
                    merged.merge(current, weight, ScenarioMigrations::strongerWeight);
                }
            });
            contraintes.put("poids", merged);
        }
        if (contraintes.get("niveaux") instanceof Map<?, ?> niveaux) {
            Map<String, Object> merged = new LinkedHashMap<>();
            niveaux.forEach((name, niveau) -> {
                String current = ConstraintCatalog.currentName(String.valueOf(name));
                if (niveau == null) {
                    merged.putIfAbsent(current, null);
                } else {
                    merged.merge(current, niveau, ScenarioMigrations::harderLevel);
                }
            });
            contraintes.put("niveaux", merged);
        }
        Map<String, Object> result = new LinkedHashMap<>(document);
        result.put("contraintes", contraintes);
        return result;
    }

    /**
     * The 3.x hard form of the run of days ({@link
     * ConstraintCatalog#FORME_DURE_JOURS_CONSECUTIFS}) names a level, not a
     * rule (ADR 0118), so it is folded here rather than renamed: listed in
     * {@code activees}, the merged rule becomes {@code OBLIGATOIRE} in {@code
     * niveaux} and is switched on; anywhere else — {@code desactivees}, its
     * shipped state, or a hard weight — it is dropped, and the merged rule keeps
     * what the file said of the preference form. Applied to every document,
     * like {@link #renameRetiredRules}.
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> foldHardRunForm(Map<String, Object> document, List<String> warnings) {
        String hard = ConstraintCatalog.FORME_DURE_JOURS_CONSECUTIFS;
        String merged = ConstraintCatalog.currentName(hard);
        if (!(document.get("contraintes") instanceof Map<?, ?> raw)) {
            return document;
        }
        Map<String, Object> contraintes = new LinkedHashMap<>((Map<String, Object>) raw);
        boolean mentioned = false;
        boolean on = false;
        for (String key : List.of("desactivees", "activees")) {
            List<String> names = listOf(contraintes.get(key));
            if (names.contains(hard)) {
                mentioned = true;
                on |= "activees".equals(key);
                contraintes.put(
                        key, names.stream().filter(name -> !hard.equals(name)).toList());
            }
        }
        for (String key : List.of("poids", "niveaux")) {
            if (contraintes.get(key) instanceof Map<?, ?> byName && byName.containsKey(hard)) {
                mentioned = true;
                Map<String, Object> copy = new LinkedHashMap<>((Map<String, Object>) byName);
                copy.remove(hard);
                contraintes.put(key, copy);
            }
        }
        if (!mentioned) {
            return document;
        }
        if (on) {
            List<String> disabled = new ArrayList<>(listOf(contraintes.get("desactivees")));
            disabled.remove(merged);
            putIfPresent(contraintes, "desactivees", disabled);
            Map<String, Object> niveaux = contraintes.get("niveaux") instanceof Map<?, ?> byName
                    ? new LinkedHashMap<>((Map<String, Object>) byName)
                    : new LinkedHashMap<>();
            niveaux.put(merged, ConstraintCatalog.NiveauRegle.OBLIGATOIRE.name());
            contraintes.put("niveaux", niveaux);
        }
        warnings.add("La forme dure « " + hard + " » a été fusionnée dans « " + merged + " », dont le niveau se "
                + "choisit désormais : "
                + (on
                        ? "allumée dans le fichier, elle est lue comme « " + merged + " » obligatoire."
                        : "éteinte dans le fichier, elle ne change rien à « " + merged + " ».")
                + " Retirez la clé « " + hard + " » pour ne plus voir cet avertissement.");
        Map<String, Object> result = new LinkedHashMap<>(document);
        result.put("contraintes", contraintes);
        return result;
    }

    private static void noteRename(String name, Set<String> successors, List<String> warnings) {
        String current = ConstraintCatalog.currentName(name);
        if (current.equals(name)) {
            return;
        }
        successors.add(current);
        String warning = "La règle « " + name + " » a été fusionnée dans « " + current + " » (une seule règle"
                + " pour les deux âges, la limite étant lue selon l'âge) : le fichier est lu avec la règle « " + current
                + " », selon la lecture la plus protectrice des anciens réglages. Renommez la clé pour ne plus"
                + " voir cet avertissement.";
        if (!warnings.contains(warning)) {
            warnings.add(warning);
        }
    }

    private static List<String> listOf(Object value) {
        return value instanceof List<?> items
                ? items.stream().map(String::valueOf).toList()
                : List.of();
    }

    private static void putIfPresent(Map<String, Object> contraintes, String key, List<String> names) {
        if (contraintes.containsKey(key)) {
            contraintes.put(key, names);
        }
    }

    private static Object strongerWeight(Object a, Object b) {
        return a instanceof Number x && b instanceof Number y && y.longValue() > x.longValue() ? b : a;
    }

    private static Object harderLevel(Object a, Object b) {
        int rankA = NIVEAUX_PAR_PROTECTION.indexOf(String.valueOf(a).trim());
        int rankB = NIVEAUX_PAR_PROTECTION.indexOf(String.valueOf(b).trim());
        return rankB >= 0 && (rankA < 0 || rankB < rankA) ? b : a;
    }

    /**
     * 3 → 4: {@code contraintes.niveaux} (the level an edition chooses per
     * organisation rule, ADR 0106) joins the file. Nothing to convert: a v3 file
     * names no level, and a rule with none follows the catalogue's own — what
     * every v3 edition scored under. The version is raised all the same, so that
     * a file carrying levels is refused by a reader that does not know them
     * (« format plus récent ») rather than read with them silently dropped.
     */
    static Map<String, Object> v3ToV4(Map<String, Object> document, List<String> warnings) {
        return document;
    }

    /**
     * 5 → 6: the day templates are the templates of the one timeslot generator
     * (ADR 0126), so the {@code journeesTypes:} section is now {@code gabarits:},
     * entry for entry — name, vacations and dates unchanged. A file carrying
     * both keeps {@code gabarits}, and says the other one was dropped.
     */
    static Map<String, Object> v5ToV6(Map<String, Object> document, List<String> warnings) {
        if (!document.containsKey("journeesTypes")) {
            return document;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        document.forEach((key, value) -> {
            if (!"journeesTypes".equals(key)) {
                result.put(key, value);
            } else if (!document.containsKey("gabarits")) {
                result.put("gabarits", value);
            } else {
                warnings.add("La section « journeesTypes » est ignorée : le fichier porte déjà « gabarits », qui la"
                        + " remplace depuis la 4.0.");
            }
        });
        return result;
    }

    /**
     * 6 → 7: {@code couverturePause}, the flag that halved the seats of a meal
     * relay, leaves the model — no rule ever read it. It is dropped from every
     * {@code creneaux} entry and every {@code gabarits[].vacations} entry that
     * carries it, and said once with the number of entries: the file is read, the
     * stand is then staffed at its full headcount on those shifts. Kept through
     * 4.x, retired in 5.0.
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> v6ToV7(Map<String, Object> document, List<String> warnings) {
        Map<String, Object> result = new LinkedHashMap<>(document);
        int[] dropped = {0};
        result.computeIfPresent("creneaux", (key, list) -> withoutFlag(list, dropped));
        result.computeIfPresent("gabarits", (key, list) -> {
            if (!(list instanceof List<?> gabarits)) {
                return list;
            }
            List<Object> converted = new ArrayList<>();
            for (Object gabarit : gabarits) {
                if (gabarit instanceof Map<?, ?> map && map.get("vacations") != null) {
                    Map<String, Object> copy = new LinkedHashMap<>((Map<String, Object>) map);
                    copy.put("vacations", withoutFlag(map.get("vacations"), dropped));
                    converted.add(copy);
                } else {
                    converted.add(gabarit);
                }
            }
            return converted;
        });
        if (dropped[0] > 0) {
            warnings.add("La clé « couverturePause » est ignorée sur " + dropped[0] + " créneau(x) ou vacation(s) : le"
                    + " relais repas à effectif réduit n'existe plus depuis la 4.0, le stand y est servi à son effectif"
                    + " plein.");
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Object withoutFlag(Object list, int[] dropped) {
        if (!(list instanceof List<?> entries)) {
            return list;
        }
        List<Object> converted = new ArrayList<>();
        for (Object entry : entries) {
            if (entry instanceof Map<?, ?> map && map.containsKey("couverturePause")) {
                Map<String, Object> copy = new LinkedHashMap<>((Map<String, Object>) map);
                if (Boolean.TRUE.equals(copy.remove("couverturePause"))) {
                    dropped[0]++;
                }
                converted.add(copy);
            } else {
                converted.add(entry);
            }
        }
        return converted;
    }

    /**
     * 1 → 2, the version that opens the 4.x chain; the links of the model
     * changes of 4.0 are folded into it, as 4.0 is not released yet:
     * <ul>
     *   <li>{@code typologies[].ninja} becomes {@code polyvalent} (a file that
     *       already carries {@code polyvalent} keeps it).</li>
     * </ul>
     */
    static Map<String, Object> v1ToV2(Map<String, Object> document, List<String> warnings) {
        if (!(document.get("typologies") instanceof List<?> typologies)) {
            return document;
        }
        List<Object> converted = new ArrayList<>();
        for (Object item : typologies) {
            if (item instanceof Map<?, ?> typologie && typologie.containsKey("ninja")) {
                Map<String, Object> renamed = new LinkedHashMap<>();
                typologie.forEach((key, value) -> {
                    if ("ninja".equals(key)) {
                        renamed.putIfAbsent("polyvalent", value);
                    } else {
                        renamed.put(String.valueOf(key), value);
                    }
                });
                converted.add(renamed);
            } else {
                converted.add(item);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>(document);
        result.put("typologies", converted);
        return result;
    }

    /**
     * The {@code plan:} section's locks of a retired type (ADR 0109) rewritten
     * as what they froze. Not a link of the chain: 4.0 is not released, and
     * the unreleased builds wrote that section with the old types in version 3
     * and in version 4 alike, so no version marks the change — the conversion
     * applies to every document, and leaves one without such a lock as it is.
     */
    static Map<String, Object> withRetiredLockTypesConverted(Map<String, Object> document, List<String> warnings) {
        if (!(document.get("plan") instanceof Map<?, ?> plan)
                || !(plan.get("verrous") instanceof List<?> verrous)
                || verrous.stream().noneMatch(ScenarioMigrations::isRetired)) {
            return document;
        }
        Map<String, Object> converted = new LinkedHashMap<>();
        plan.forEach((key, value) -> converted.put(String.valueOf(key), value));
        converted.put(
                "verrous",
                convertRetiredLocks(
                        plan.get("affectations") instanceof List<?> seats ? seats : List.of(), verrous, warnings));
        Map<String, Object> result = new LinkedHashMap<>(document);
        result.put("plan", converted);
        return result;
    }

    private static final Set<String> RETIRED_LOCK_TYPES = Set.of("ANIMATEUR", "STAND", "CRENEAU");

    private static boolean isRetired(Object lock) {
        return lock instanceof Map<?, ?> map && RETIRED_LOCK_TYPES.contains(String.valueOf(map.get("type")));
    }

    /**
     * The locks of a plan, a retired type (ANIMATEUR, STAND, CRENEAU) replaced
     * by what it froze on that plan's seats — the conversion migration V151
     * makes in the database, read here off a file or the plan column of a 3.x
     * backup, which keep the shape both use: seats carry {@code standId},
     * {@code date}, {@code heureDebut}, {@code heureFin}, {@code animateurId};
     * a lock carries {@code type}, {@code animateurId}, {@code standId},
     * {@code creneauDate}, {@code creneauHeureDebut}, {@code creneauHeureFin},
     * {@code jour}, {@code raison}.
     * <ul>
     *   <li>ANIMATEUR → one ANIMATEUR_CRENEAU per timeslot the animateur holds a seat on;</li>
     *   <li>STAND → one STAND_JOUR per day the stand has seats;</li>
     *   <li>CRENEAU → one ANIMATEUR_CRENEAU per animateur holding a seat on that timeslot.</li>
     * </ul>
     * A lock no seat matches froze nothing and is dropped. Each conversion is
     * said in {@code warnings}, naming ids only. Other locks pass unchanged.
     */
    public static List<Object> convertRetiredLocks(List<?> seats, List<?> locks, List<String> warnings) {
        List<Map<?, ?>> places = seats.stream()
                .filter(Map.class::isInstance)
                .<Map<?, ?>>map(Map.class::cast)
                .toList();
        List<Object> result = new ArrayList<>();
        for (Object item : locks) {
            if (!isRetired(item)) {
                result.add(item);
                continue;
            }
            Map<?, ?> lock = (Map<?, ?>) item;
            String type = String.valueOf(lock.get("type"));
            Set<Map<String, Object>> replacements = new LinkedHashSet<>();
            switch (type) {
                case "ANIMATEUR" ->
                    places.stream()
                            .filter(sameAs(lock, "animateurId", "animateurId"))
                            .forEach(seat -> replacements.add(lock(
                                    "ANIMATEUR_CRENEAU",
                                    lock.get("animateurId"),
                                    null,
                                    seat.get("date"),
                                    seat.get("heureDebut"),
                                    seat.get("heureFin"),
                                    null,
                                    lock.get("raison"))));
                case "STAND" ->
                    places.stream()
                            .filter(sameAs(lock, "standId", "standId"))
                            .forEach(seat -> replacements.add(lock(
                                    "STAND_JOUR",
                                    null,
                                    lock.get("standId"),
                                    null,
                                    null,
                                    null,
                                    seat.get("date"),
                                    lock.get("raison"))));
                default ->
                    places.stream()
                            .filter(sameAs(lock, "creneauDate", "date")
                                    .and(sameAs(lock, "creneauHeureDebut", "heureDebut"))
                                    .and(sameAs(lock, "creneauHeureFin", "heureFin"))
                                    .and(seat -> seat.get("animateurId") != null))
                            .forEach(seat -> replacements.add(lock(
                                    "ANIMATEUR_CRENEAU",
                                    seat.get("animateurId"),
                                    null,
                                    lock.get("creneauDate"),
                                    lock.get("creneauHeureDebut"),
                                    lock.get("creneauHeureFin"),
                                    null,
                                    lock.get("raison"))));
            }
            result.addAll(replacements);
            String cible = Objects.requireNonNullElse(
                            lock.get("animateurId"),
                            Objects.requireNonNullElse(
                                    lock.get("standId"),
                                    lock.get("creneauDate") + " " + lock.get("creneauHeureDebut") + "-"
                                            + lock.get("creneauHeureFin")))
                    .toString();
            warnings.add(
                    replacements.isEmpty()
                            ? "Verrou " + type + " (" + cible
                                    + ") : type retiré en 4.0, aucune place ne lui correspond dans"
                                    + " le planning, il ne figeait rien et n'est pas repris."
                            : "Verrou " + type + " (" + cible + ") : type retiré en 4.0, converti en "
                                    + replacements.size()
                                    + " verrou(s) " + ("STAND".equals(type) ? "stand + journée" : "animateur + créneau")
                                    + ", les places qu'il figeait.");
        }
        return result;
    }

    private static Predicate<Map<?, ?>> sameAs(Map<?, ?> lock, String lockKey, String seatKey) {
        Object wanted = comparable(lock.get(lockKey));
        return seat -> wanted != null && wanted.equals(comparable(seat.get(seatKey)));
    }

    /** A date or an hour compared as what it means, whichever way the file wrote it. */
    private static Object comparable(Object value) {
        Object normalised = ScenarioYaml.normaliseDates(value);
        if (!(normalised instanceof String text)) {
            return normalised;
        }
        for (Function<String, Object> parser : List.<Function<String, Object>>of(LocalDate::parse, LocalTime::parse)) {
            try {
                return parser.apply(text);
            } catch (DateTimeParseException _) {
                // Not that kind of value.
            }
        }
        return text;
    }

    private static Map<String, Object> lock(
            String type,
            Object animateurId,
            Object standId,
            Object creneauDate,
            Object creneauHeureDebut,
            Object creneauHeureFin,
            Object jour,
            Object raison) {
        Map<String, Object> lock = new LinkedHashMap<>();
        lock.put("type", type);
        lock.put("animateurId", animateurId);
        lock.put("standId", standId);
        lock.put("creneauDate", creneauDate);
        lock.put("creneauHeureDebut", creneauHeureDebut);
        lock.put("creneauHeureFin", creneauHeureFin);
        lock.put("jour", jour);
        lock.put("raison", raison);
        return lock;
    }

    private static final Set<String> RETIRED_AD_HOC_TYPES = Set.of("INDISPONIBILITE_FORCEE", "AFFECTATION_FORCEE");

    /**
     * The {@code contraintesAdHoc:} section's forced unavailabilities and forced
     * assignments (ADR 0117) rewritten as what replaces them, the conversion
     * migration V161 makes in the database. Not a link of the chain, for the
     * reason {@link #withRetiredLockTypesConverted} gives: no version marks the
     * change, so it applies to every document.
     * <ul>
     *   <li>INDISPONIBILITE_FORCEE without a stand → the day of its timeslot (every day
     *       carrying a timeslot when it names none) added to {@code joursIndisponibles} of
     *       each animateur it names. A timeslot becomes the <b>whole day</b>: said.</li>
     *   <li>AFFECTATION_FORCEE on a timeslot → one ANIMATEUR_CRENEAU lock per named
     *       animateur holding a seat of its scope in the {@code plan:} section. A file with
     *       no plan, or with nobody on the seat, has nothing to lock.</li>
     * </ul>
     * What converts to nothing (a forced unavailability on a stand, a forced assignment
     * with no seat held) is dropped <b>and said</b>, naming the exception's id.
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> withRetiredAdHocTypesConverted(Map<String, Object> document, List<String> warnings) {
        if (!(document.get("contraintesAdHoc") instanceof List<?> exceptions)
                || exceptions.stream().noneMatch(ScenarioMigrations::isRetiredAdHoc)) {
            return document;
        }
        Map<String, String> datesByCreneau = new LinkedHashMap<>();
        Set<String> eventDays = new java.util.TreeSet<>();
        if (document.get("creneaux") instanceof List<?> creneaux) {
            for (Object item : creneaux) {
                if (item instanceof Map<?, ?> creneau && creneau.get("date") != null) {
                    String date = String.valueOf(ScenarioYaml.normaliseDates(creneau.get("date")));
                    datesByCreneau.put(String.valueOf(creneau.get("id")), date);
                    eventDays.add(date);
                }
            }
        }
        Map<String, Set<String>> daysOff = new LinkedHashMap<>();
        List<Object> locks = new ArrayList<>();
        List<Object> kept = new ArrayList<>();
        List<Map<?, ?>> seats =
                document.get("plan") instanceof Map<?, ?> plan && plan.get("affectations") instanceof List<?> list
                        ? list.stream()
                                .filter(Map.class::isInstance)
                                .<Map<?, ?>>map(Map.class::cast)
                                .toList()
                        : List.of();
        for (Object item : exceptions) {
            if (!isRetiredAdHoc(item)) {
                kept.add(item);
                continue;
            }
            Map<?, ?> exception = (Map<?, ?>) item;
            String id = String.valueOf(exception.get("id"));
            List<?> named = exception.get("animateurs") instanceof List<?> ids ? ids : List.of();
            String creneauId = exception.get("creneauId") == null ? null : String.valueOf(exception.get("creneauId"));
            Object standId = exception.get("standId");
            if ("INDISPONIBILITE_FORCEE".equals(String.valueOf(exception.get("type")))) {
                if (standId != null || named.isEmpty()) {
                    warnings.add("Exception " + id + " (indisponibilité forcée sur un stand) : type retiré en 4.0,"
                            + " rien à convertir sur la fiche, elle n'est pas reprise.");
                    continue;
                }
                Set<String> days = creneauId == null
                        ? eventDays
                        : datesByCreneau.get(creneauId) == null
                                ? Set.<String>of()
                                : Set.of(datesByCreneau.get(creneauId));
                named.forEach(animateur -> daysOff.computeIfAbsent(
                                String.valueOf(animateur), key -> new java.util.TreeSet<>())
                        .addAll(days));
                warnings.add("Exception " + id + " (indisponibilité forcée) : type retiré en 4.0, convertie en"
                        + (creneauId == null
                                ? " jours indisponibles de la fiche (tous les jours du fichier)."
                                : " JOUR ENTIER indisponible sur la fiche : la fiche ne connaît pas le créneau."));
                continue;
            }
            Map<?, ?> creneau = creneauId == null ? null : creneauWindow(document, creneauId);
            Set<Map<String, Object>> replacements = new LinkedHashSet<>();
            if (creneau != null) {
                for (Object animateur : named) {
                    boolean holds = seats.stream()
                            .anyMatch(seat -> animateur.equals(seat.get("animateurId"))
                                    && Objects.equals(comparable(seat.get("date")), comparable(creneau.get("date")))
                                    && Objects.equals(
                                            comparable(seat.get("heureDebut")), comparable(creneau.get("heureDebut")))
                                    && Objects.equals(
                                            comparable(seat.get("heureFin")), comparable(creneau.get("heureFin")))
                                    && (standId == null || standId.equals(seat.get("standId"))));
                    if (holds) {
                        replacements.add(lock(
                                "ANIMATEUR_CRENEAU",
                                animateur,
                                null,
                                ScenarioYaml.normaliseDates(creneau.get("date")),
                                ScenarioYaml.normaliseDates(creneau.get("heureDebut")),
                                ScenarioYaml.normaliseDates(creneau.get("heureFin")),
                                null,
                                exception.get("raison")));
                    }
                }
            }
            if (replacements.isEmpty()) {
                warnings.add("Exception " + id + " (affectation forcée) : type retiré en 4.0, aucune place tenue dans"
                        + " le planning du fichier à verrouiller, elle n'est pas reprise.");
            } else {
                locks.addAll(replacements);
                warnings.add("Exception " + id + " (affectation forcée) : type retiré en 4.0, convertie en "
                        + replacements.size() + " verrou(s) animateur + créneau, les places tenues.");
            }
        }
        Map<String, Object> result = new LinkedHashMap<>(document);
        result.put("contraintesAdHoc", kept);
        if (!daysOff.isEmpty() && document.get("animateurs") instanceof List<?> animateurs) {
            List<Object> converted = new ArrayList<>();
            for (Object item : animateurs) {
                if (item instanceof Map<?, ?> fiche && daysOff.containsKey(String.valueOf(fiche.get("id")))) {
                    Map<String, Object> copy = new LinkedHashMap<>();
                    fiche.forEach((key, value) -> copy.put(String.valueOf(key), value));
                    Set<String> days = new java.util.TreeSet<>(daysOff.get(String.valueOf(fiche.get("id"))));
                    if (fiche.get("joursIndisponibles") instanceof List<?> existing) {
                        existing.forEach(day -> days.add(String.valueOf(ScenarioYaml.normaliseDates(day))));
                    }
                    copy.put("joursIndisponibles", new ArrayList<>(days));
                    converted.add(copy);
                } else {
                    converted.add(item);
                }
            }
            result.put("animateurs", converted);
        }
        if (!locks.isEmpty() && document.get("plan") instanceof Map<?, ?> plan) {
            Map<String, Object> converted = new LinkedHashMap<>();
            plan.forEach((key, value) -> converted.put(String.valueOf(key), value));
            List<Object> verrous =
                    new ArrayList<>(plan.get("verrous") instanceof List<?> existing ? existing : List.of());
            verrous.addAll(locks);
            converted.put("verrous", verrous);
            result.put("plan", converted);
        }
        return result;
    }

    private static boolean isRetiredAdHoc(Object exception) {
        return exception instanceof Map<?, ?> map && RETIRED_AD_HOC_TYPES.contains(String.valueOf(map.get("type")));
    }

    private static Map<?, ?> creneauWindow(Map<String, Object> document, String creneauId) {
        return document.get("creneaux") instanceof List<?> creneaux
                ? creneaux.stream()
                        .filter(Map.class::isInstance)
                        .<Map<?, ?>>map(Map.class::cast)
                        .filter(creneau -> creneauId.equals(String.valueOf(creneau.get("id"))))
                        .findFirst()
                        .orElse(null)
                : null;
    }
}
