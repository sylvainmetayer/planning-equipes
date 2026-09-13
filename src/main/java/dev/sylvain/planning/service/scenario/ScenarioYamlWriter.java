package dev.sylvain.planning.service.scenario;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.ContrainteAdHoc;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.Emplacement;
import dev.sylvain.planning.domain.JourneeType;
import dev.sylvain.planning.domain.ParametresLegaux;
import dev.sylvain.planning.domain.ParametresSolveur;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.scenario.ScenarioYaml;
import dev.sylvain.planning.service.referentiel.JourneesTypesMaterialisation;
import dev.sylvain.planning.service.referentiel.TypologieItem;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

/**
 * Writes the YAML text of a scenario file. Pure and static: it takes the data
 * it serializes as an argument and reads nothing else, which is why it can sit
 * outside {@link PlanningService} and be unit-tested without a database.
 *
 * <p>It used to be a hand-written traversal building a {@code Map} key by key,
 * facing a reader that was another hand-written traversal of the same shape —
 * two descriptions of one format, kept in step by hand. Since A2 (issue #392)
 * it serialises a {@code ScenarioDto}: <b>what the application writes is the
 * DTO</b>, so the JSON Schema published from that record describes the file
 * rather than resembling it.</p>
 *
 * <p>What is left here is the transport: assemble, serialise, dump. Which key
 * appears, in what order, and which one is left out is decided by the record —
 * see {@link ScenarioDtoAssembler} and {@link ScenarioYaml#writer()}.</p>
 */
public final class ScenarioYamlWriter {

    private ScenarioYamlWriter() {}

    /**
     * Everything one exported scenario file holds. A record rather than ten
     * positional parameters, since {@link #buildScenarioYaml} is called both
     * from {@link PlanningService#exportScenarioYaml()} and from its unit tests.
     *
     * @param postes an explicit seat list to pin in the file, or {@code null}
     *               to leave the section out. Production exports always pass
     *               {@code null}: the import regenerates the seats from the
     *               stands and créneaux (see {@link PlanningService#exportScenarioYaml()}).
     *               Only tests write one, to exercise how a pinned list reads back
     */
    public record ScenarioExport(
            List<Animateur> animateurs,
            List<Stand> stands,
            List<Creneau> creneaux,
            List<PosteAffectation> postes,
            List<TypologieItem> typologies,
            List<Emplacement> emplacements,
            ParametresLegaux parametresLegaux,
            ParametresSolveur parametresSolveur,
            Set<String> contraintesDesactivees,
            Map<String, Integer> poidsContraintes,
            List<ContrainteAdHoc> contraintesAdHoc,
            List<JourneeType> journeesTypes,
            List<JourneesTypesMaterialisation.Affectation> calendrierJourneesTypes) {

        /** Without day templates — the tests that predate them, and an edition that has none. */
        public ScenarioExport(
                List<Animateur> animateurs,
                List<Stand> stands,
                List<Creneau> creneaux,
                List<PosteAffectation> postes,
                List<TypologieItem> typologies,
                List<Emplacement> emplacements,
                ParametresLegaux parametresLegaux,
                ParametresSolveur parametresSolveur,
                Set<String> contraintesDesactivees,
                Map<String, Integer> poidsContraintes,
                List<ContrainteAdHoc> contraintesAdHoc) {
            this(
                    animateurs,
                    stands,
                    creneaux,
                    postes,
                    typologies,
                    emplacements,
                    parametresLegaux,
                    parametresSolveur,
                    contraintesDesactivees,
                    poidsContraintes,
                    contraintesAdHoc,
                    List.of(),
                    List.of());
        }
    }

    /**
     * <b>Visible for testing only</b> — no production caller, and none should
     * appear.
     *
     * <p>It writes the four entity sections and <em>nothing else</em>: no
     * {@code parametresLegaux} or {@code parametresSolveur}, no typologies, no emplacements. That is exactly
     * the amputated file {@link PlanningService#exportScenarioYaml()} documents
     * as a fixed bug — one that silently fell back on the importing instance's
     * own settings. Real exports go through {@link #buildScenarioYaml(ScenarioExport)}.</p>
     *
     * <p>Package-private and static, like {@link ProblemBuilder#buildPostes}, so
     * the tests need no database.</p>
     */
    public static String buildScenarioYaml(
            List<Animateur> animateurs, List<Stand> stands, List<Creneau> creneaux, List<PosteAffectation> postes) {
        return buildScenarioYaml(new ScenarioExport(
                animateurs, stands, creneaux, postes, List.of(), List.of(), null, null, Set.of(), Map.of(), List.of()));
    }

    /** Full-fidelity variant: writes every optional section {@link ScenarioExport} carries. */
    public static String buildScenarioYaml(ScenarioExport export) {
        Object document = ScenarioYaml.writer().convertValue(ScenarioDtoAssembler.assemble(export), Map.class);

        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        // Dumped with SnakeYAML's own resolver, deliberately — not the one
        // ScenarioYaml parses with. The emitter uses it to decide what needs
        // quoting, and YAML 1.1 reads a bare `13:00` as the sexagesimal number
        // 780: quoting it is what keeps the file readable by an older instance
        // of this application, or by any other YAML tool. Our parser no longer
        // resolves sexagesimals, but a file we write is not only read by us.
        // (`09:00` comes out bare, and is safe: a leading zero puts it outside
        // the pattern.)
        // No anchors to guard against here any more: Jackson builds a fresh map
        // and a fresh list for every node, so no two of them are the same
        // instance — which is what used to make SnakeYAML emit `&id001` and an
        // alias per animateur sharing one empty list, producing a file the
        // application then refused to re-import.
        // PlanningServiceScenarioAllerRetourTest holds that property.
        return new Yaml(options).dump(document);
    }
}
