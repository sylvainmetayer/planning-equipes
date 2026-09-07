package dev.sylvain.planning.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.ContrainteAdHoc;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.Emplacement;
import dev.sylvain.planning.domain.FenetreHoraire;
import dev.sylvain.planning.domain.HoraireStand;
import dev.sylvain.planning.domain.IndisponibiliteStand;
import dev.sylvain.planning.domain.OuvertureStand;
import dev.sylvain.planning.domain.ParametresDecoupage;
import dev.sylvain.planning.domain.ParametresLegaux;
import dev.sylvain.planning.domain.ParametresSolveur;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;

/**
 * Writes the YAML text of a scenario file. Pure and static: it takes the data
 * it serializes as an argument and reads nothing else, which is why it can sit
 * outside {@link PlanningService} and be unit-tested without a database.
 *
 * <p>Its counterpart on the way in is {@link ScenarioYamlReader#loadReferenceScenario(String)}:
 * the two are hand-written traversals of the same {@code Map} shape and have to
 * be kept in step by hand. {@code PlanningServiceScenarioAllerRetourTest} is
 * what actually holds them together — it exports and re-imports, so a field
 * written here and not read there fails the build.</p>
 */
final class ScenarioYamlWriter {

    private ScenarioYamlWriter() {
    }

    /**
     * Everything one exported scenario file holds. A record rather than ten
     * positional parameters, since {@link #buildScenarioYaml} is called both
     * from {@link PlanningService#exportScenarioYaml()} and from its unit tests.
     *
     * @param postes the seat list, or {@code null} to leave the section out
     *               (see {@link PlanningService#exportScenarioYaml()})
     */
    record ScenarioExport(
            List<Animateur> animateurs,
            List<Stand> stands,
            List<Creneau> creneaux,
            List<PosteAffectation> postes,
            List<TypologieItem> typologies,
            List<Emplacement> emplacements,
            ParametresLegaux parametresLegaux,
            ParametresDecoupage parametresDecoupage,
            ParametresSolveur parametresSolveur,
            Set<String> contraintesDesactivees,
            Map<String, Integer> poidsContraintes,
            List<ContrainteAdHoc> contraintesAdHoc) {
    }

    /**
     * <b>Visible for testing only</b> — no production caller, and none should
     * appear.
     *
     * <p>It writes the four entity sections and <em>nothing else</em>: no
     * {@code parametresLegaux}, {@code parametresDecoupage} or
     * {@code parametresSolveur}, no typologies, no emplacements. That is exactly
     * the amputated file {@link PlanningService#exportScenarioYaml()} documents
     * as a fixed bug — one that silently fell back on the importing instance's
     * own settings. Real exports go through {@link #buildScenarioYaml(ScenarioExport)}.</p>
     *
     * <p>Package-private and static, like {@link ProblemBuilder#buildPostes}, so
     * the tests need no database.</p>
     */
    static String buildScenarioYaml(List<Animateur> animateurs, List<Stand> stands, List<Creneau> creneaux,
            List<PosteAffectation> postes) {
        return buildScenarioYaml(new ScenarioExport(animateurs, stands, creneaux, postes, List.of(), List.of(),
                null, null, null, Set.of(), Map.of(), List.of()));
    }

    /** Full-fidelity variant: writes every optional section {@link ScenarioExport} carries. */
    static String buildScenarioYaml(ScenarioExport export) {
        List<Animateur> animateurs = export.animateurs();
        List<Stand> stands = export.stands();
        List<Creneau> creneaux = export.creneaux();
        List<PosteAffectation> postes = export.postes();
        LocalDate dateDebut = creneaux.stream()
                .map(Creneau::getDate)
                .filter(Objects::nonNull)
                .min(LocalDate::compareTo)
                .orElse(null);

        Map<String, Object> evenement = new LinkedHashMap<>();
        evenement.put("dateDebut", asString(dateDebut));

        List<Map<String, Object>> creneauxYaml = new ArrayList<>();
        for (Creneau creneau : creneaux) {
            Map<String, Object> item = new LinkedHashMap<>();
            // asString, as everywhere else: the id is a Long in the database,
            // but the published schema declares it `string` and the loader
            // reads it back as one. Writing it raw produced a YAML number that
            // no import could read — see the round trip covered by
            // PlanningServiceScenarioAllerRetourTest.
            item.put("id", asString(creneau.getId()));
            item.put("jour", creneau.getJour());
            item.put("date", asString(creneau.getDate()));
            item.put("heureDebut", asString(creneau.getHeureDebut()));
            item.put("heureFin", asString(creneau.getHeureFin()));
            creneauxYaml.add(item);
        }

        List<Map<String, Object>> standsYaml = new ArrayList<>();
        for (Stand stand : stands) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", stand.getId());
            item.put("nom", stand.getNom());
            if (stand.getEmplacement() != null) {
                item.put("emplacementId", stand.getEmplacement().getId());
            }
            item.put("typologiesProposees", new ArrayList<>(stand.getTypologiesProposees()));
            item.put("effectifMin", stand.getEffectifMin());
            item.put("effectifMax", stand.getEffectifMax());
            item.put("reserveMajeurs", stand.isReserveMajeurs());
            item.put("premium", stand.isPremium());
            item.put("niveauEffort", stand.getNiveauEffort().name());
            if (stand.getFamille() != null) {
                item.put("famille", stand.getFamille());
            }
            item.put("indisponibilites", indisponibilitesYaml(stand.getIndisponibilites()));
            item.put("ouvertures", ouverturesYaml(stand.getOuvertures()));
            item.put("horaires", horairesYaml(stand.getHoraires()));
            standsYaml.add(item);
        }

        List<Map<String, Object>> animateursYaml = new ArrayList<>();
        for (Animateur animateur : animateurs) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", animateur.getId());
            item.put("prenom", animateur.getPrenom());
            item.put("nom", animateur.getNom());
            item.put("dateNaissance", asString(animateur.getDateNaissance()));
            item.put("manager", animateur.isManager());
            // Contact only — the espace-animateur access token never travels
            // through a scenario file (regenerated from the database instead).
            if (animateur.getEmail() != null && !animateur.getEmail().isBlank()) {
                item.put("email", animateur.getEmail());
            }
            Map<String, String> competences = new LinkedHashMap<>();
            if (animateur.getCompetences() != null) {
                animateur.getCompetences()
                        .forEach((typologie, niveau) -> competences.put(typologie, niveau.name()));
            }
            item.put("competences", competences);
            List<String> joursIndisponibles = animateur.getJoursIndisponibles() == null
                    ? List.of()
                    : animateur.getJoursIndisponibles().stream()
                            .sorted()
                            .map(ScenarioYamlWriter::asString)
                            .toList();
            item.put("joursIndisponibles", joursIndisponibles);
            List<String> souhaits = animateur.getSouhaits() == null
                    ? List.of()
                    : new ArrayList<>(animateur.getSouhaits());
            item.put("souhaits", souhaits);
            animateursYaml.add(item);
        }

        // null (not empty): a scenario carrying decoupageAuto must not pin a seat
        // list, since the créneaux it would reference only exist after the
        // découpage has run on import.
        List<Map<String, Object>> postesYaml = postes == null ? null : new ArrayList<>();
        for (PosteAffectation poste : postes == null ? List.<PosteAffectation>of() : postes) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", poste.getId());
            item.put("standId", poste.getStand().getId());
            item.put("creneauId", asString(poste.getCreneau().getId()));
            item.put("animateurId", null);
            postesYaml.add(item);
        }

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("festival", evenement);
        if (export.parametresSolveur() != null) {
            root.put("parametresSolveur",
                    Map.of("dureeResolutionSecondes", export.parametresSolveur().dureeResolutionSecondes()));
        }
        if (export.parametresLegaux() != null) {
            root.put("parametresLegaux", parametresLegauxYaml(export.parametresLegaux()));
        }
        if (export.parametresDecoupage() != null) {
            root.put("parametresDecoupage", parametresDecoupageYaml(export.parametresDecoupage()));
        }
        Map<String, Object> contraintes = contraintesYaml(export);
        if (contraintes != null) {
            root.put("contraintes", contraintes);
        }
        if (!export.typologies().isEmpty()) {
            root.put("typologies", typologiesYaml(export.typologies()));
        }
        root.put("creneaux", creneauxYaml);
        if (!export.emplacements().isEmpty()) {
            root.put("emplacements", emplacementsYaml(export.emplacements()));
        }
        root.put("stands", standsYaml);
        root.put("animateurs", animateursYaml);
        if (postesYaml != null) {
            root.put("postes", postesYaml);
        }
        if (export.contraintesAdHoc() != null && !export.contraintesAdHoc().isEmpty()) {
            root.put("contraintesAdHoc", contraintesAdHocYaml(export.contraintesAdHoc(), creneaux));
        }

        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        return new Yaml(options).dump(deepCopy(root));
    }

    /**
     * Rebuilds the structure so that no two nodes are the same instance.
     *
     * <p>SnakeYAML writes an anchor and an alias ({@code &id001} / {@code *id001})
     * as soon as one collection appears twice, and {@code List.of()} returns a
     * singleton — so a hundred and fifty animateurs with no day off shared one
     * empty list, and the export carried an alias for every one of them. Our
     * own file import reads YAML through Jackson, which does not resolve those
     * aliases: the application refused the scenario it had just written, with
     * « Cannot deserialize value of type ArrayList&lt;LocalDate&gt; from String
     * value ». Scalars are exempt — SnakeYAML never anchors them — so copying
     * the maps and the lists is enough, and it is done once here rather than
     * left to every builder above to remember.</p>
     */
    private static Object deepCopy(Object valeur) {
        if (valeur instanceof Map<?, ?> map) {
            Map<Object, Object> copie = new LinkedHashMap<>();
            map.forEach((clef, valeurDeLaClef) -> copie.put(clef, deepCopy(valeurDeLaClef)));
            return copie;
        }
        if (valeur instanceof List<?> liste) {
            List<Object> copie = new ArrayList<>(liste.size());
            liste.forEach(element -> copie.add(deepCopy(element)));
            return copie;
        }
        return valeur;
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }

    /**
     * The {@code contraintes:} section, or {@code null} when the edition tunes
     * nothing — every rule active at its default weight is what a file without
     * the section already means, and writing it out would be noise.
     */
    private static Map<String, Object> contraintesYaml(ScenarioExport export) {
        Set<String> desactivees = export.contraintesDesactivees() == null ? Set.of() : export.contraintesDesactivees();
        Map<String, Integer> poids = export.poidsContraintes() == null ? Map.of() : export.poidsContraintes();
        if (desactivees.isEmpty() && poids.isEmpty()) {
            return null;
        }
        Map<String, Object> item = new LinkedHashMap<>();
        if (!desactivees.isEmpty()) {
            item.put("desactivees", new TreeSet<>(desactivees).stream().toList());
        }
        if (!poids.isEmpty()) {
            item.put("poids", new TreeMap<>(poids));
        }
        return item;
    }

    /**
     * Serializes the hand-entered constraints, translating the créneau's
     * database id back into the text id the {@code creneaux:} section of the
     * very same file uses — the export writes {@code creneau.getId()} there,
     * so the two halves stay tied together whatever the ids become on import.
     */
    private static List<Map<String, Object>> contraintesAdHocYaml(List<ContrainteAdHoc> contraintes,
            List<Creneau> creneaux) {
        Set<Long> creneauxConnus = creneaux.stream().map(Creneau::getId).collect(Collectors.toSet());
        List<Map<String, Object>> result = new ArrayList<>();
        for (ContrainteAdHoc contrainte : contraintes) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", contrainte.getId());
            item.put("type", contrainte.getType().name());
            item.put("animateurs", contrainte.getAnimateursConcernes().stream()
                    .filter(Objects::nonNull)
                    .map(Animateur::getId)
                    .toList());
            // A constraint aiming at a créneau the export does not carry would
            // be refused on import: drop the scope rather than the constraint,
            // it then covers the whole event, which is the safe side for
            // every prescriptive type.
            if (contrainte.getCreneau() != null && creneauxConnus.contains(contrainte.getCreneau().getId())) {
                item.put("creneauId", asString(contrainte.getCreneau().getId()));
            }
            if (contrainte.getStand() != null) {
                item.put("standId", contrainte.getStand().getId());
            }
            if (contrainte.getRaison() != null) {
                item.put("raison", contrainte.getRaison());
            }
            result.add(item);
        }
        return result;
    }

    /** Only the four fields a scenario file is read back with (see {@link ScenarioYamlReader.ScenarioSections}). */
    private static Map<String, Object> parametresLegauxYaml(ParametresLegaux parametres) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("dureeHebdomadaireMaxMinutes", parametres.getDureeHebdomadaireMaxMinutes());
        item.put("pauseMinimaleEntreVacationsMinutes", parametres.getPauseMinimaleEntreVacationsMinutes());
        item.put("reposQuotidienMinimalMinutes", parametres.getReposQuotidienMinimalMinutes());
        item.put("pauseSurPoste", parametres.isPauseSurPoste());
        return item;
    }

    private static Map<String, Object> parametresDecoupageYaml(ParametresDecoupage parametres) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("dureeVacationCibleMinutes", parametres.getDureeVacationCibleMinutes());
        item.put("dureeVacationMinMinutes", parametres.getDureeVacationMinMinutes());
        item.put("dureeVacationMaxMinutes", parametres.getDureeVacationMaxMinutes());
        item.put("dureeChevauchementMinutes", parametres.getDureeChevauchementMinutes());
        item.put("dureePauseRepasMinutes", parametres.getDureePauseRepasMinutes());
        item.put("fenetreRepasMidiDebut", asString(parametres.getFenetreRepasMidiDebut()));
        item.put("fenetreRepasMidiFin", asString(parametres.getFenetreRepasMidiFin()));
        item.put("fenetreRepasSoirDebut", asString(parametres.getFenetreRepasSoirDebut()));
        item.put("fenetreRepasSoirFin", asString(parametres.getFenetreRepasSoirFin()));
        item.put("strategieCouverturePendantPause", parametres.getStrategieCouverturePendantPause().name());
        item.put("nombreFamillesDecalage", parametres.getNombreFamillesDecalage());
        item.put("dureeDecalageMaxMinutes", parametres.getDureeDecalageMaxMinutes());
        item.put("modeGrille", parametres.getModeGrille().name());
        return item;
    }

    private static List<Map<String, Object>> typologiesYaml(List<TypologieItem> typologies) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (TypologieItem typologie : typologies) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", typologie.id());
            item.put("label", typologie.label());
            item.put("ninja", typologie.ninja());
            result.add(item);
        }
        return result;
    }

    private static List<Map<String, Object>> emplacementsYaml(List<Emplacement> emplacements) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Emplacement emplacement : emplacements) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", emplacement.getId());
            item.put("nom", emplacement.getNom());
            item.put("latitude", emplacement.getLatitude());
            item.put("longitude", emplacement.getLongitude());
            result.add(item);
        }
        return result;
    }

    /**
     * Serializes a stand's {@link IndisponibiliteStand} closures to the shape
     * {@link ScenarioYamlReader#loadReferenceScenario(String)} reads back.
     */
    private static List<Map<String, Object>> indisponibilitesYaml(List<IndisponibiliteStand> indisponibilites) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (IndisponibiliteStand indispo : indisponibilites) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("date", asString(indispo.getDate()));
            item.put("heureDebut", asString(indispo.getHeureDebut()));
            item.put("heureFin", asString(indispo.getHeureFin()));
            item.put("motif", indispo.getMotif());
            result.add(item);
        }
        return result;
    }

    /**
     * Serializes a stand's {@link OuvertureStand} openings to the shape
     * {@link ScenarioYamlReader#loadReferenceScenario(String)} reads back.
     */
    private static List<Map<String, Object>> ouverturesYaml(List<OuvertureStand> ouvertures) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (OuvertureStand ouverture : ouvertures) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("date", asString(ouverture.getDate()));
            item.put("heureDebut", asString(ouverture.getHeureDebut()));
            item.put("heureFin", asString(ouverture.getHeureFin()));
            item.put("motif", ouverture.getMotif());
            item.put("effectif", ouverture.getEffectif());
            result.add(item);
        }
        return result;
    }

    /**
     * Serializes a stand's recurring {@link HoraireStand} rules to the shape
     * {@link ScenarioYamlReader#loadReferenceScenario(String)} reads back — the day selector flattened
     * onto the rule itself, so the common "every day" case stays a two-line
     * entry and the reader needs no polymorphism.
     *
     * <p>Only the fields the selector actually uses are written: a {@code TOUS}
     * rule carries no dates, so emitting empty {@code dates}/{@code dateDebut}
     * keys would be noise in a file meant to be read and diffed by hand.</p>
     */
    private static List<Map<String, Object>> horairesYaml(List<HoraireStand> horaires) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (HoraireStand horaire : horaires) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("mode", horaire.getMode().name());
            item.put("jours", horaire.getJours().name());
            switch (horaire.getJours()) {
                case JOURS_SEMAINE -> item.put("joursSemaine",
                        horaire.getJoursSemaine().stream().map(Enum::name).toList());
                case PLAGE -> {
                    item.put("dateDebut", asString(horaire.getDateDebut()));
                    item.put("dateFin", asString(horaire.getDateFin()));
                }
                case DATES -> item.put("dates", horaire.getDates().stream().map(ScenarioYamlWriter::asString).toList());
                case TOUS -> {
                    // No selector data to write.
                }
            }
            List<Map<String, Object>> fenetres = new ArrayList<>();
            for (FenetreHoraire fenetre : horaire.getFenetres()) {
                Map<String, Object> fenetreYaml = new LinkedHashMap<>();
                fenetreYaml.put("heureDebut", asString(fenetre.getHeureDebut()));
                // Absent rather than null: "jusqu'à la fermeture" reads better as
                // a missing end than as an explicit empty one.
                if (fenetre.getHeureFin() != null) {
                    fenetreYaml.put("heureFin", asString(fenetre.getHeureFin()));
                }
                // Absent for the same reason: no effectif means "inherit
                // effectifMin", and writing it out would freeze today's value
                // into the file as if it had been chosen.
                if (fenetre.getEffectif() != null) {
                    fenetreYaml.put("effectif", fenetre.getEffectif());
                }
                fenetres.add(fenetreYaml);
            }
            item.put("fenetres", fenetres);
            if (horaire.getMotif() != null) {
                item.put("motif", horaire.getMotif());
            }
            result.add(item);
        }
        return result;
    }
}
