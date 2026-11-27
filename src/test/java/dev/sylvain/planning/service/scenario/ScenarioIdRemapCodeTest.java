package dev.sylvain.planning.service.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.scenario.dto.EmplacementDto;
import dev.sylvain.planning.scenario.dto.ScenarioDto;
import dev.sylvain.planning.scenario.dto.StandDto;
import dev.sylvain.planning.scenario.dto.TypologieDto;
import dev.sylvain.planning.service.IdGenerator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.Test;

/**
 * A code designates one row of the edition: the remap gives a reference the
 * row carrying its code before the row of its id, so that no write of the
 * import lands a code on one row while another still holds it.
 */
class ScenarioIdRemapCodeTest {

    @Test
    void aCodeLeftByTheRowOfItsIdIsFoundOnTheRowThatTookIt() {
        Map<String, String> existants = new HashMap<>();
        existants.put("L1", null);
        existants.put("L2", "PLACE");

        ScenarioDto remappe = remap(
                Map.of(IdGenerator.Kind.EMPLACEMENT, existants),
                List.of(new EmplacementDto("L1", "PLACE", "Place", null, null)),
                List.of(),
                List.of());

        assertThat(remappe.emplacements()).singleElement().satisfies(dto -> {
            assertThat(dto.id()).isEqualTo("L2");
            assertThat(dto.code()).isEqualTo("PLACE");
        });
    }

    @Test
    void aCodeIsClaimedBeforeAnIdWhateverTheOrderOfTheFile() {
        Map<String, String> existants = new HashMap<>();
        existants.put("T1", "STRATEGIE");
        existants.put("T2", null);

        // T2 is read first and would keep its id, writing STRATEGIE while T1 still holds it.
        ScenarioDto remappe = remap(
                Map.of(IdGenerator.Kind.TYPOLOGIE, existants),
                List.of(),
                List.of(
                        new TypologieDto("T2", "STRATEGIE", "Stratégie", null, null, null),
                        new TypologieDto("T1", null, "Sans code", null, null, null)),
                List.of());

        assertThat(remappe.typologies())
                .extracting(TypologieDto::id, TypologieDto::code)
                .containsExactly(Tuple.tuple("T1", "STRATEGIE"), Tuple.tuple("T3", null));
    }

    @Test
    void aStandCodeIsClaimedBeforeAnIdWhateverTheOrderOfTheFile() {
        Map<String, String> existants = new HashMap<>();
        existants.put("S1", "STAND-A");
        existants.put("S2", null);

        ScenarioDto remappe = remap(
                Map.of(IdGenerator.Kind.STAND, existants),
                List.of(),
                List.of(),
                List.of(stand("S2", "STAND-A"), stand("S1", null)));

        assertThat(remappe.stands())
                .extracting(StandDto::id, StandDto::code)
                .containsExactly(Tuple.tuple("S1", "STAND-A"), Tuple.tuple("S3", null));
    }

    private static StandDto stand(String id, String code) {
        return new StandDto(id, code, "Stand", null, List.of("T1"), 1, 1, null, null, null, null, null);
    }

    private static ScenarioDto remap(
            Map<IdGenerator.Kind, Map<String, String>> codes,
            List<EmplacementDto> emplacements,
            List<TypologieDto> typologies,
            List<StandDto> stands) {
        ScenarioDto scenario = new ScenarioDto(
                2,
                null,
                null,
                null,
                null,
                typologies,
                List.of(),
                null,
                emplacements,
                stands,
                List.of(),
                null,
                null,
                null,
                null,
                null);
        return ScenarioIdRemap.remap(scenario, new Edition(codes));
    }

    /** An edition whose counters stand at 2: L1, L2, T1, T2 were handed out. */
    private static final class Edition implements ScenarioIdRemap.Edition {

        private final Map<IdGenerator.Kind, Map<String, String>> codes;

        private final Map<IdGenerator.Kind, Long> derniers = new EnumMap<>(IdGenerator.Kind.class);

        Edition(Map<IdGenerator.Kind, Map<String, String>> codes) {
            this.codes = codes;
        }

        @Override
        public Map<String, String> codesById(IdGenerator.Kind kind) {
            return codes.getOrDefault(kind, Map.of());
        }

        @Override
        public List<ScenarioIdRemap.AnimateurConnu> animateurs() {
            return List.of();
        }

        @Override
        public Set<String> contraintes() {
            return Set.of();
        }

        @Override
        public String nextId(IdGenerator.Kind kind) {
            long numero = derniers.merge(kind, 1L, Long::sum) + 2;
            return kind.prefix() + numero;
        }

        @Override
        public long lastNumber(IdGenerator.Kind kind) {
            return 2;
        }

        @Override
        public void raise(IdGenerator.Kind kind, long number) {
            // The counter stands above every id of these files.
        }
    }
}
