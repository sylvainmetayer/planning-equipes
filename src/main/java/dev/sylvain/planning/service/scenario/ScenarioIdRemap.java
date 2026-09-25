package dev.sylvain.planning.service.scenario;

import dev.sylvain.planning.domain.NiveauCompetence;
import dev.sylvain.planning.scenario.dto.AnimateurDto;
import dev.sylvain.planning.scenario.dto.ConsigneDto;
import dev.sylvain.planning.scenario.dto.ContrainteAdHocDto;
import dev.sylvain.planning.scenario.dto.EmplacementDto;
import dev.sylvain.planning.scenario.dto.OuvertureConsigneDto;
import dev.sylvain.planning.scenario.dto.PosteDto;
import dev.sylvain.planning.scenario.dto.ScenarioDto;
import dev.sylvain.planning.scenario.dto.StandDto;
import dev.sylvain.planning.scenario.dto.TypologieDto;
import dev.sylvain.planning.service.IdGenerator;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Turns the ids a scenario file carries into the ids of the edition it is
 * imported into (ADR 0050) — the precedent of the timeslots, extended to every
 * referential.
 *
 * <p>An id in a file is a <b>reference local to that file</b>: what its seats,
 * constraints and consignes cite. What it designates in the edition is decided
 * here, row by row, in this order:</p>
 * <ol>
 *   <li>the row of that id, when the edition has one and it is plausibly the
 *       same thing — for an animateur, the same e-mail or the same name; for a
 *       stand, a typologie or an emplacement, no contradicting code. A file
 *       exported from this very edition is re-imported in place, tokens and
 *       all, which is what keeps the espace links printed on the PDFs alive.
 *       The check matters because every edition numbers from 1: {@code A3} of
 *       another edition is somebody else, and matching on the id alone would
 *       hand this edition's {@code A3}'s espace to them;</li>
 *   <li>the row carrying the file's {@code code} — or its id, read as a code,
 *       for a file written by hand ({@code STRATEGIE}) or exported before ids
 *       were generated; for an animateur, the one fiche of that e-mail, then
 *       the one fiche of that name;</li>
 *   <li>a new row — under the file's own id when it has the referential's
 *       shape and its number was never handed out in the edition (a file
 *       exported from an edition and imported into a new one keeps its ids,
 *       as a duplication does), under an id drawn from the edition's counter
 *       otherwise; keeping the file's id as its code when it is readable.</li>
 * </ol>
 *
 * <p>« Never handed out » is the counter's own rule: a number freed by a
 * deletion is not given to somebody else, so a file id at or below the
 * counter is drawn afresh even when no row carries it any more.</p>
 *
 * <p>Pure: everything it needs from the edition comes through
 * {@link Edition}, so its tests need no database.</p>
 */
final class ScenarioIdRemap {

    /** What the target edition already holds, and where new ids come from. */
    interface Edition {

        /** Existing rows of a coded referential: id → code ({@code null} when none). */
        Map<String, String> codesById(IdGenerator.Kind kind);

        List<AnimateurConnu> animateurs();

        Set<String> contraintes();

        String nextId(IdGenerator.Kind kind);

        /** The last number the counter of {@code kind} handed out. */
        long lastNumber(IdGenerator.Kind kind);

        /** Marks the numbers up to {@code number} as handed out, so that {@link #nextId} never draws them. */
        void raise(IdGenerator.Kind kind, long number);
    }

    /** An existing animateur, as much of it as matching needs. */
    record AnimateurConnu(String id, String email, String prenom, String nom) {}

    private final Edition edition;

    /** Per referential, the file ids a new row may keep: of the right shape, above the counter. */
    private final Map<IdGenerator.Kind, Set<String>> gardables = new java.util.EnumMap<>(IdGenerator.Kind.class);

    private final Map<String, String> typologies = new LinkedHashMap<>();
    private final Map<String, String> typologieCodes = new LinkedHashMap<>();
    private final Map<String, String> emplacements = new LinkedHashMap<>();
    private final Map<String, String> emplacementCodes = new LinkedHashMap<>();
    private final Map<String, String> stands = new LinkedHashMap<>();
    private final Map<String, String> standCodes = new LinkedHashMap<>();
    private final Map<String, String> animateurs = new LinkedHashMap<>();
    private final Map<String, String> contraintes = new LinkedHashMap<>();

    private ScenarioIdRemap(Edition edition) {
        this.edition = edition;
    }

    /** The same scenario, every business id rewritten into the target edition's. */
    static ScenarioDto remap(ScenarioDto scenario, Edition edition) {
        return new ScenarioIdRemap(edition).apply(scenario);
    }

    private ScenarioDto apply(ScenarioDto scenario) {
        reserveKeptIds(scenario);
        mapTypologies(scenario);
        mapEmplacements(scenario);
        mapStands(scenario);
        mapAnimateurs(scenario);
        mapContraintes(scenario);
        return new ScenarioDto(
                scenario.edition(),
                scenario.festival(),
                scenario.parametresSolveur(),
                scenario.parametresLegaux(),
                scenario.parametresQualite(),
                scenario.contraintes(),
                typologiesSection(scenario),
                scenario.creneaux(),
                scenario.journeesTypes(),
                emplacementsSection(scenario),
                standsSection(scenario),
                animateursSection(scenario),
                postes(scenario.postes()),
                contraintesAdHoc(scenario.contraintesAdHoc()),
                scenario.prereglagesConsigne(),
                consignes(scenario.consignes()));
    }

    /* ------------------------------ Matching ------------------------------ */

    /**
     * Finds, per referential, the file ids a new row may keep, and raises the
     * counter past them before any id is drawn — so that a drawn id never
     * lands on one the file is about to keep.
     */
    private void reserveKeptIds(ScenarioDto scenario) {
        Map<IdGenerator.Kind, Set<String>> references = new java.util.EnumMap<>(IdGenerator.Kind.class);
        for (IdGenerator.Kind kind : IdGenerator.Kind.values()) {
            references.put(kind, new LinkedHashSet<>());
        }
        list(scenario.typologies())
                .forEach(dto -> references.get(IdGenerator.Kind.TYPOLOGIE).add(dto.id()));
        list(scenario.emplacements())
                .forEach(dto -> references.get(IdGenerator.Kind.EMPLACEMENT).add(dto.id()));
        for (StandDto stand : list(scenario.stands())) {
            references.get(IdGenerator.Kind.STAND).add(stand.id());
            references.get(IdGenerator.Kind.TYPOLOGIE).addAll(list(stand.typologiesProposees()));
            if (stand.emplacementId() != null) {
                references.get(IdGenerator.Kind.EMPLACEMENT).add(stand.emplacementId());
            }
        }
        for (AnimateurDto animateur : list(scenario.animateurs())) {
            references.get(IdGenerator.Kind.ANIMATEUR).add(animateur.id());
            if (animateur.competences() != null) {
                references
                        .get(IdGenerator.Kind.TYPOLOGIE)
                        .addAll(animateur.competences().keySet());
            }
            references.get(IdGenerator.Kind.TYPOLOGIE).addAll(list(animateur.souhaits()));
        }
        list(scenario.contraintesAdHoc())
                .forEach(dto -> references.get(IdGenerator.Kind.CONTRAINTE).add(dto.id()));
        references.forEach((kind, ids) -> {
            long dernier = edition.lastNumber(kind);
            Set<String> gardes = new LinkedHashSet<>();
            long plusHaut = 0;
            for (String id : ids) {
                long numero = IdGenerator.numberOf(kind, id);
                if (numero > dernier) {
                    gardes.add(id);
                    plusHaut = Math.max(plusHaut, numero);
                }
            }
            if (plusHaut > 0) {
                edition.raise(kind, plusHaut);
            }
            gardables.put(kind, gardes);
        });
    }

    /** A new row's id: the file's own when it may keep it, a drawn one otherwise. */
    private String newId(IdGenerator.Kind kind, String reference, java.util.Collection<String> pris) {
        if (gardables.get(kind).contains(reference) && !pris.contains(reference)) {
            return reference;
        }
        return edition.nextId(kind);
    }

    /**
     * The typologies the file declares first, then the ones its stands and
     * animateurs merely cite — numbered in the order the file meets them.
     */
    private void mapTypologies(ScenarioDto scenario) {
        Map<String, String> declares = new LinkedHashMap<>();
        for (TypologieDto dto : list(scenario.typologies())) {
            declares.putIfAbsent(dto.id(), dto.code());
        }
        Set<String> references = new LinkedHashSet<>(declares.keySet());
        for (StandDto stand : list(scenario.stands())) {
            references.addAll(list(stand.typologiesProposees()));
        }
        for (AnimateurDto animateur : list(scenario.animateurs())) {
            if (animateur.competences() != null) {
                references.addAll(animateur.competences().keySet());
            }
            references.addAll(list(animateur.souhaits()));
        }
        Map<String, String> existants = edition.codesById(IdGenerator.Kind.TYPOLOGIE);
        for (String reference : references) {
            if (reference == null) {
                continue;
            }
            mapCoded(
                    IdGenerator.Kind.TYPOLOGIE,
                    reference,
                    declares.get(reference),
                    existants,
                    typologies,
                    typologieCodes);
        }
    }

    private void mapEmplacements(ScenarioDto scenario) {
        Map<String, String> declares = new LinkedHashMap<>();
        for (EmplacementDto dto : list(scenario.emplacements())) {
            declares.putIfAbsent(dto.id(), dto.code());
        }
        Set<String> references = new LinkedHashSet<>(declares.keySet());
        for (StandDto stand : list(scenario.stands())) {
            if (stand.emplacementId() != null) {
                references.add(stand.emplacementId());
            }
        }
        Map<String, String> existants = edition.codesById(IdGenerator.Kind.EMPLACEMENT);
        for (String reference : references) {
            mapCoded(
                    IdGenerator.Kind.EMPLACEMENT,
                    reference,
                    declares.get(reference),
                    existants,
                    emplacements,
                    emplacementCodes);
        }
    }

    private void mapStands(ScenarioDto scenario) {
        Map<String, String> existants = edition.codesById(IdGenerator.Kind.STAND);
        for (StandDto stand : list(scenario.stands())) {
            if (stand.id() != null && !stands.containsKey(stand.id())) {
                mapCoded(IdGenerator.Kind.STAND, stand.id(), stand.code(), existants, stands, standCodes);
            }
        }
    }

    /**
     * One reference of a coded referential: the row of that id when no code
     * contradicts it, else the row of that code, else a new row. Two
     * references never land on the same row.
     */
    private void mapCoded(
            IdGenerator.Kind kind,
            String reference,
            String codeDeclare,
            Map<String, String> existants,
            Map<String, String> ids,
            Map<String, String> codes) {
        if (ids.containsKey(reference)) {
            return;
        }
        String code = blankAsNull(codeDeclare);
        Set<String> pris = new HashSet<>(ids.values());
        String cible = null;
        if (existants.containsKey(reference) && !pris.contains(reference)) {
            String codeExistant = existants.get(reference);
            if (code == null || codeExistant == null || code.equals(codeExistant)) {
                cible = reference;
            }
        }
        String cle = code != null ? code : (kind.hasGeneratedShape(reference) ? null : reference);
        if (cible == null && cle != null) {
            cible = existants.entrySet().stream()
                    .filter(existant -> cle.equals(existant.getValue()) && !pris.contains(existant.getKey()))
                    .map(Map.Entry::getKey)
                    .findFirst()
                    .orElse(null);
        }
        if (cible == null) {
            cible = newId(kind, reference, pris);
            codes.put(reference, cle);
        } else {
            codes.put(reference, code != null ? code : existants.get(cible));
        }
        ids.put(reference, cible);
    }

    private void mapAnimateurs(ScenarioDto scenario) {
        List<AnimateurConnu> connus = edition.animateurs();
        Map<String, AnimateurConnu> parId = new LinkedHashMap<>();
        connus.forEach(connu -> parId.put(connu.id(), connu));
        Set<String> pris = new HashSet<>();
        for (AnimateurDto dto : list(scenario.animateurs())) {
            if (dto.id() == null || animateurs.containsKey(dto.id())) {
                continue;
            }
            AnimateurConnu memeId = parId.get(dto.id());
            String cible =
                    memeId != null && !pris.contains(memeId.id()) && samePerson(dto, memeId) ? memeId.id() : null;
            if (cible == null && blankAsNull(dto.email()) != null) {
                cible = unique(connus, pris, connu -> equalsIgnoreCase(connu.email(), dto.email()));
            }
            if (cible == null) {
                cible = unique(
                        connus,
                        pris,
                        connu -> equalsIgnoreCase(connu.prenom(), dto.prenom())
                                && equalsIgnoreCase(connu.nom(), dto.nom()));
            }
            if (cible == null) {
                cible = newId(IdGenerator.Kind.ANIMATEUR, dto.id(), pris);
            }
            pris.add(cible);
            animateurs.put(dto.id(), cible);
        }
    }

    /** Same e-mail when both carry one, same name otherwise. */
    private static boolean samePerson(AnimateurDto dto, AnimateurConnu connu) {
        if (blankAsNull(dto.email()) != null && blankAsNull(connu.email()) != null) {
            return equalsIgnoreCase(dto.email(), connu.email());
        }
        return equalsIgnoreCase(dto.prenom(), connu.prenom()) && equalsIgnoreCase(dto.nom(), connu.nom());
    }

    /** The one unclaimed fiche the test accepts; none when zero or several do — an ambiguity is not a match. */
    private static String unique(List<AnimateurConnu> connus, Set<String> pris, Predicate<AnimateurConnu> test) {
        List<AnimateurConnu> candidats = connus.stream()
                .filter(connu -> !pris.contains(connu.id()))
                .filter(test)
                .toList();
        return candidats.size() == 1 ? candidats.getFirst().id() : null;
    }

    /**
     * Every ad hoc constraint is replaced by the import, so only its id needs
     * choosing: kept when the edition has it, drawn otherwise.
     */
    private void mapContraintes(ScenarioDto scenario) {
        Set<String> existantes = edition.contraintes();
        Set<String> pris = new HashSet<>();
        for (ContrainteAdHocDto dto : list(scenario.contraintesAdHoc())) {
            if (dto.id() == null || contraintes.containsKey(dto.id())) {
                continue;
            }
            String cible = existantes.contains(dto.id()) && !pris.contains(dto.id())
                    ? dto.id()
                    : newId(IdGenerator.Kind.CONTRAINTE, dto.id(), pris);
            pris.add(cible);
            contraintes.put(dto.id(), cible);
        }
    }

    /* ------------------------------ Rewriting ----------------------------- */

    /**
     * The file's own entries, rewritten — plus one entry per typologie the
     * file only cites and that did not exist: the planning import would create
     * it with its id for label, and a label reading « T9 » is no label.
     */
    private List<TypologieDto> typologiesSection(ScenarioDto scenario) {
        List<TypologieDto> section = new ArrayList<>();
        Set<String> declares = new HashSet<>();
        for (TypologieDto dto : list(scenario.typologies())) {
            declares.add(dto.id());
            section.add(new TypologieDto(
                    typologies.getOrDefault(dto.id(), dto.id()),
                    typologieCodes.get(dto.id()),
                    dto.label(),
                    dto.ninja(),
                    dto.maxCreneauxParAnimateur(),
                    dto.description()));
        }
        Map<String, String> existants = edition.codesById(IdGenerator.Kind.TYPOLOGIE);
        typologies.forEach((reference, id) -> {
            if (!declares.contains(reference) && !existants.containsKey(id)) {
                String code = typologieCodes.get(reference);
                section.add(new TypologieDto(id, code, code != null ? code : reference, null, null, null));
            }
        });
        return section.isEmpty() && scenario.typologies() == null ? null : section;
    }

    private List<EmplacementDto> emplacementsSection(ScenarioDto scenario) {
        if (scenario.emplacements() == null) {
            return null;
        }
        return scenario.emplacements().stream()
                .map(dto -> new EmplacementDto(
                        emplacements.getOrDefault(dto.id(), dto.id()),
                        emplacementCodes.get(dto.id()),
                        dto.nom(),
                        dto.latitude(),
                        dto.longitude()))
                .toList();
    }

    private List<StandDto> standsSection(ScenarioDto scenario) {
        if (scenario.stands() == null) {
            return null;
        }
        return scenario.stands().stream()
                .map(dto -> new StandDto(
                        stands.getOrDefault(dto.id(), dto.id()),
                        standCodes.get(dto.id()),
                        dto.nom(),
                        dto.emplacementId() == null
                                ? null
                                : emplacements.getOrDefault(dto.emplacementId(), dto.emplacementId()),
                        dto.typologiesProposees() == null
                                ? null
                                : dto.typologiesProposees().stream()
                                        .map(typologie -> typologies.getOrDefault(typologie, typologie))
                                        .toList(),
                        dto.effectifMin(),
                        dto.effectifMax(),
                        dto.reserveMajeurs(),
                        dto.premium(),
                        dto.niveauEffort(),
                        dto.indisponibilites(),
                        dto.ouvertures(),
                        dto.horaires()))
                .toList();
    }

    private List<AnimateurDto> animateursSection(ScenarioDto scenario) {
        if (scenario.animateurs() == null) {
            return null;
        }
        return scenario.animateurs().stream()
                .map(dto -> {
                    Map<String, NiveauCompetence> competences = null;
                    if (dto.competences() != null) {
                        competences = new LinkedHashMap<>();
                        for (Map.Entry<String, NiveauCompetence> competence :
                                dto.competences().entrySet()) {
                            competences.put(
                                    typologies.getOrDefault(competence.getKey(), competence.getKey()),
                                    competence.getValue());
                        }
                    }
                    return new AnimateurDto(
                            animateurs.getOrDefault(dto.id(), dto.id()),
                            dto.prenom(),
                            dto.nom(),
                            dto.dateNaissance(),
                            dto.manager(),
                            dto.email(),
                            competences,
                            dto.joursIndisponibles(),
                            dto.souhaits() == null
                                    ? null
                                    : dto.souhaits().stream()
                                            .map(souhait -> typologies.getOrDefault(souhait, souhait))
                                            .toList());
                })
                .toList();
    }

    private List<PosteDto> postes(List<PosteDto> postes) {
        if (postes == null) {
            return null;
        }
        return postes.stream()
                .map(dto -> new PosteDto(
                        dto.id(),
                        stands.getOrDefault(dto.standId(), dto.standId()),
                        dto.creneauId(),
                        dto.animateurId() == null
                                ? null
                                : animateurs.getOrDefault(dto.animateurId(), dto.animateurId())))
                .toList();
    }

    private List<ContrainteAdHocDto> contraintesAdHoc(List<ContrainteAdHocDto> contraintesAdHoc) {
        if (contraintesAdHoc == null) {
            return null;
        }
        return contraintesAdHoc.stream()
                .map(dto -> new ContrainteAdHocDto(
                        contraintes.getOrDefault(dto.id(), dto.id()),
                        dto.type(),
                        dto.animateurs() == null
                                ? null
                                : dto.animateurs().stream()
                                        .map(animateur -> animateurs.getOrDefault(animateur, animateur))
                                        .toList(),
                        dto.creneauId(),
                        dto.standId() == null ? null : stands.getOrDefault(dto.standId(), dto.standId()),
                        dto.raison()))
                .toList();
    }

    private List<ConsigneDto> consignes(List<ConsigneDto> consignes) {
        if (consignes == null) {
            return null;
        }
        return consignes.stream()
                .map(dto -> new ConsigneDto(
                        dto.date(),
                        dto.fermetureDebut(),
                        dto.fermetureFin(),
                        dto.motif(),
                        dto.prereglage(),
                        dto.fenetres(),
                        dto.ouvertures() == null
                                ? null
                                : dto.ouvertures().stream()
                                        .map(ouverture -> new OuvertureConsigneDto(
                                                stands.getOrDefault(ouverture.standId(), ouverture.standId()),
                                                ouverture.debut(),
                                                ouverture.fin(),
                                                ouverture.effectif()))
                                        .toList(),
                        dto.creneauxAjoutes(),
                        dto.repas()))
                .toList();
    }

    /* -------------------------------- Tools ------------------------------- */

    private static <T> List<T> list(List<T> values) {
        return values == null
                ? List.of()
                : values.stream().filter(Objects::nonNull).toList();
    }

    private static String blankAsNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static boolean equalsIgnoreCase(String a, String b) {
        return a != null
                && b != null
                && a.trim().toLowerCase(Locale.ROOT).equals(b.trim().toLowerCase(Locale.ROOT));
    }
}
