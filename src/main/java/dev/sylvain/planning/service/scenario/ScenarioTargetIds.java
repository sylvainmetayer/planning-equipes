package dev.sylvain.planning.service.scenario;

import dev.sylvain.planning.domain.ContrainteAdHoc;
import dev.sylvain.planning.domain.Emplacement;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.IdGenerator;
import dev.sylvain.planning.service.referentiel.ReferenceDataService;
import dev.sylvain.planning.service.referentiel.TypologieItem;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** The current edition, as {@link ScenarioIdRemap} reads it: its rows, and its id counters. */
@ApplicationScoped
class ScenarioTargetIds {

    @Inject
    ReferenceDataService referenceDataService;

    @Inject
    IdGenerator ids;

    /** A view of the current edition, read now: call it inside the edition the import writes into. */
    ScenarioIdRemap.Edition of() {
        Map<IdGenerator.Kind, Map<String, String>> codes = new HashMap<>();
        codes.put(IdGenerator.Kind.STAND, codesOf(referenceDataService.listStands()));
        codes.put(IdGenerator.Kind.EMPLACEMENT, emplacementCodes(referenceDataService.listEmplacements()));
        Map<String, String> typologies = new HashMap<>();
        for (TypologieItem typologie : referenceDataService.listTypologies()) {
            typologies.put(typologie.id(), typologie.code());
        }
        codes.put(IdGenerator.Kind.TYPOLOGIE, typologies);
        List<ScenarioIdRemap.AnimateurConnu> animateurs = referenceDataService.listAnimateurs().stream()
                .map(animateur -> new ScenarioIdRemap.AnimateurConnu(
                        animateur.getId(), animateur.getEmail(), animateur.getPrenom(), animateur.getNom()))
                .toList();
        Set<String> contraintes = referenceDataService.listContraintesAdHoc().stream()
                .map(ContrainteAdHoc::getId)
                .collect(Collectors.toSet());
        return new ScenarioIdRemap.Edition() {
            @Override
            public Map<String, String> codesById(IdGenerator.Kind kind) {
                return codes.getOrDefault(kind, Map.of());
            }

            @Override
            public List<ScenarioIdRemap.AnimateurConnu> animateurs() {
                return animateurs;
            }

            @Override
            public Set<String> contraintes() {
                return contraintes;
            }

            @Override
            public String nextId(IdGenerator.Kind kind) {
                return ids.next(kind);
            }

            @Override
            public long lastNumber(IdGenerator.Kind kind) {
                return ids.lastNumber(kind);
            }

            @Override
            public void raise(IdGenerator.Kind kind, long number) {
                ids.raise(kind, number);
            }
        };
    }

    private static Map<String, String> codesOf(List<Stand> stands) {
        Map<String, String> codes = new HashMap<>();
        stands.forEach(stand -> codes.put(stand.getId(), stand.getCode()));
        return codes;
    }

    private static Map<String, String> emplacementCodes(List<Emplacement> emplacements) {
        Map<String, String> codes = new HashMap<>();
        emplacements.forEach(emplacement -> codes.put(emplacement.getId(), emplacement.getCode()));
        return codes;
    }
}
