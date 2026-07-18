package dev.sylvain.planning.service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.ContrainteAdHoc;
import dev.sylvain.planning.domain.NiveauCompetence;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.domain.StatutAnimateur;
import dev.sylvain.planning.domain.TypologieJeu;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.NotFoundException;

@ApplicationScoped
public class ReferenceDataService {

    private final Map<String, Animateur> animateurs = new ConcurrentHashMap<>();
    private final Map<String, Stand> stands = new ConcurrentHashMap<>();
    private final Map<String, ContrainteAdHoc> contraintes = new ConcurrentHashMap<>();
    private final Map<String, TypologieItem> typologies = new ConcurrentHashMap<>();

    @PostConstruct
    void init() {
        Arrays.stream(TypologieJeu.values())
                .map(value -> new TypologieItem(value.name(), value.name()))
                .forEach(item -> typologies.put(item.id(), item));

        Stand stand = new Stand("STAND-STRAT", "Stand stratégie", Set.of(TypologieJeu.STRATEGIE), 1, 2, false);
        stands.put(stand.getId(), stand);

        Animateur animateur = new Animateur("A1", "Alice", "Referente", LocalDate.now().minusYears(25), StatutAnimateur.BENEVOLE);
        animateur.setCompetences(Map.of(TypologieJeu.STRATEGIE, NiveauCompetence.REFERENT));
        animateurs.put(animateur.getId(), animateur);
    }

    public List<Animateur> listAnimateurs() {
        return sortedCopy(animateurs);
    }

    public Animateur createAnimateur(Animateur animateur) {
        animateur.setId(requiredId(animateur.getId(), "animateur id"));
        animateurs.put(animateur.getId(), animateur);
        return animateur;
    }

    public Animateur updateAnimateur(String id, Animateur animateur) {
        if (!animateurs.containsKey(id)) {
            throw new NotFoundException("Animateur not found: " + id);
        }
        animateur.setId(id);
        animateurs.put(id, animateur);
        return animateur;
    }

    public void deleteAnimateur(String id) {
        animateurs.remove(id);
    }

    public List<Stand> listStands() {
        return sortedCopy(stands);
    }

    public Stand createStand(Stand stand) {
        stand.setId(requiredId(stand.getId(), "stand id"));
        stands.put(stand.getId(), stand);
        return stand;
    }

    public Stand updateStand(String id, Stand stand) {
        if (!stands.containsKey(id)) {
            throw new NotFoundException("Stand not found: " + id);
        }
        stand.setId(id);
        stands.put(id, stand);
        return stand;
    }

    public void deleteStand(String id) {
        stands.remove(id);
    }

    public List<TypologieItem> listTypologies() {
        return sortedCopy(typologies);
    }

    public TypologieItem createTypologie(TypologieItem typologie) {
        String id = requiredId(typologie.id(), "typology id");
        TypologieItem created = new TypologieItem(id, typologie.label());
        typologies.put(id, created);
        return created;
    }

    public TypologieItem updateTypologie(String id, TypologieItem typologie) {
        if (!typologies.containsKey(id)) {
            throw new NotFoundException("Typology not found: " + id);
        }
        TypologieItem updated = new TypologieItem(id, typologie.label());
        typologies.put(id, updated);
        return updated;
    }

    public void deleteTypologie(String id) {
        typologies.remove(id);
    }

    public List<ContrainteAdHoc> listContraintesAdHoc() {
        return sortedCopy(contraintes);
    }

    public ContrainteAdHoc createContrainteAdHoc(ContrainteAdHoc contrainte) {
        contrainte.setId(requiredId(contrainte.getId(), "constraint id"));
        if (contrainte.getCreeLe() == null) {
            contrainte.setCreeLe(Instant.now());
        }
        contraintes.put(contrainte.getId(), contrainte);
        return contrainte;
    }

    public void deleteContrainteAdHoc(String id) {
        contraintes.remove(id);
    }

    public List<ContrainteAdHoc> snapshotContraintes() {
        return new ArrayList<>(contraintes.values());
    }

    private String requiredId(String id, String fieldName) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Missing " + fieldName);
        }
        return id;
    }

    private <T> List<T> sortedCopy(Map<String, T> map) {
        return map.entrySet().stream()
                .sorted(Comparator.comparing(Map.Entry::getKey))
                .map(Map.Entry::getValue)
                .toList();
    }

    public record TypologieItem(String id, String label) {
        public TypologieItem {
            if (label == null || label.isBlank()) {
                label = id;
            }
        }
    }
}
