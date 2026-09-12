package dev.sylvain.planning.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A named day template — « Jour normal », « Nocturne », « Montage » — the
 * vacations one kind of day holds, entered once and applied to dates. A
 * template is a <b>generator</b>, never the truth: the créneaux it materialises
 * are what the solver, the seats, the locks and the edition's bounds read, and
 * a date the calendar does not assign is left exactly as it is. See ADR 0032.
 */
public class JourneeType {

    private Long id;
    private String nom;
    private List<VacationType> vacations = new ArrayList<>();
    /** Write stamp read back by the form and sent as the write's precondition (issue #362). */
    private Instant modifieLe;

    public JourneeType() {}

    public JourneeType(Long id, String nom, List<VacationType> vacations) {
        this.id = id;
        this.nom = nom;
        this.vacations = new ArrayList<>(vacations);
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getNom() {
        return nom;
    }

    public void setNom(String nom) {
        this.nom = nom;
    }

    public List<VacationType> getVacations() {
        return vacations;
    }

    public void setVacations(List<VacationType> vacations) {
        this.vacations = vacations == null ? new ArrayList<>() : new ArrayList<>(vacations);
    }

    public Instant getModifieLe() {
        return modifieLe;
    }

    public void setModifieLe(Instant modifieLe) {
        this.modifieLe = modifieLe;
    }

    /**
     * What this template says of a day, independent of its name: the sorted
     * hours and relay flags. Two templates with the same signature describe the
     * same kind of day, which is how the recognition groups dates.
     */
    public String signature() {
        return vacations.stream()
                .map(vacation -> vacation.key() + (vacation.couverturePause() ? "R" : ""))
                .sorted()
                .reduce((a, b) -> a + "," + b)
                .orElse("");
    }
}
