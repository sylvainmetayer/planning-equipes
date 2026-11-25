package dev.sylvain.planning.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public class Stand {

    private String id;

    /**
     * The readable key of this stand in the files an organiser keeps (the CSV
     * imports, the scenario), unique in its edition, {@code null} when none
     * was given. The id is generated and means nothing (ADR 0050); the code is
     * what a file matches on.
     */
    private String code;

    private String nom;
    /** Ids referencing the {@code typologie} referential table (CRUD-managed), not a fixed enum. */
    private Set<String> typologiesProposees = new HashSet<>();

    private int effectifMin;
    private int effectifMax;
    private boolean reserveMajeurs;
    private boolean premium;
    /** Physical-effort tier; drives rest-after-effort and pénibilité-fairness constraints in QualiteConstraints/PreferenceConstraints. */
    private NiveauEffort niveauEffort = NiveauEffort.NORMAL;
    /** Physical location the stand is set up at; nullable (not every stand is geocoded). */
    private Emplacement emplacement;
    /**
     * The stand's schedule: dated opening and closing windows, nothing else
     * (ADR 0104). Empty = the stand is open on every créneau, the default. See
     * {@link PlageStand} for the per-day three-state rule and
     * {@link Creneau#segmentsOuvertsMinutes(Stand)} for how the solver reads it.
     */
    private List<PlageStand> plages = new ArrayList<>();
    /**
     * The plages to actually reason about, once something laid on top of them
     * (a consigne) has been resolved — {@code null} until
     * {@code StandService.resolve} has run, in which case {@link #plages} are.
     *
     * <p>Kept <b>beside</b> the persisted list rather than substituted into it
     * on purpose: {@code upsertStand} writes {@link #plages}, so a resolved
     * stand travelling back through a save can never freeze a consigne into
     * the database.</p>
     */
    private List<PlageStand> plagesEffectives;

    /**
     * Recurring rules a caller of the 3.x editor sent under {@code horaires},
     * waiting for {@code StandService} to lay them down as plages over the
     * edition's days. Never stored, never served. Temporary bridge, removed
     * with the plages editor (ADR 0104).
     */
    private List<HorairesRecurrents.Regle> reglesRecues = List.of();

    /**
     * When this row was last written (issue #362), read from the referential
     * and echoed back by a form on save: a write carrying a value older than
     * the row's is refused, see {@code ConcurrentModificationGuard}. {@code null}
     * on an object that never went through the database, and on a write that
     * deliberately carries no precondition (import, MCP merge, a client that
     * chose to overwrite).
     */
    private Instant modifieLe;

    public Stand() {}

    public Stand(
            String id,
            String nom,
            Set<String> typologiesProposees,
            int effectifMin,
            int effectifMax,
            boolean reserveMajeurs) {
        this(id, nom, typologiesProposees, effectifMin, effectifMax, reserveMajeurs, false);
    }

    public Stand(
            String id,
            String nom,
            Set<String> typologiesProposees,
            int effectifMin,
            int effectifMax,
            boolean reserveMajeurs,
            boolean premium) {
        this.id = id;
        this.nom = nom;
        this.typologiesProposees = typologiesProposees;
        this.effectifMin = effectifMin;
        this.effectifMax = effectifMax;
        this.reserveMajeurs = reserveMajeurs;
        this.premium = premium;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public Instant getModifieLe() {
        return modifieLe;
    }

    public void setModifieLe(Instant modifieLe) {
        this.modifieLe = modifieLe;
    }

    public String getNom() {
        return nom;
    }

    public void setNom(String nom) {
        this.nom = nom;
    }

    public Set<String> getTypologiesProposees() {
        return typologiesProposees;
    }

    public void setTypologiesProposees(Set<String> typologiesProposees) {
        this.typologiesProposees = typologiesProposees;
    }

    public int getEffectifMin() {
        return effectifMin;
    }

    public void setEffectifMin(int effectifMin) {
        this.effectifMin = effectifMin;
    }

    public int getEffectifMax() {
        return effectifMax;
    }

    public void setEffectifMax(int effectifMax) {
        this.effectifMax = effectifMax;
    }

    public boolean isReserveMajeurs() {
        return reserveMajeurs;
    }

    public void setReserveMajeurs(boolean reserveMajeurs) {
        this.reserveMajeurs = reserveMajeurs;
    }

    /** High-stakes stand: needs experienced staff and continuity (two rules read it). */
    public boolean isPremium() {
        return premium;
    }

    public void setPremium(boolean premium) {
        this.premium = premium;
    }

    public NiveauEffort getNiveauEffort() {
        return niveauEffort;
    }

    public void setNiveauEffort(NiveauEffort niveauEffort) {
        this.niveauEffort = niveauEffort != null ? niveauEffort : NiveauEffort.NORMAL;
    }

    public Emplacement getEmplacement() {
        return emplacement;
    }

    public void setEmplacement(Emplacement emplacement) {
        this.emplacement = emplacement;
    }

    /** The stored schedule — what a write changes, and the only list a save persists. */
    @JsonIgnore
    public List<PlageStand> getPlages() {
        return plages;
    }

    @JsonIgnore
    public void setPlages(List<PlageStand> plages) {
        this.plages = plages != null ? plages : new ArrayList<>();
    }

    /**
     * The closures, as a read-only view of {@link #getPlages()}. Serialized as
     * {@code indisponibilites}: the key the REST payload kept through 4.0's
     * model change, until the API speaks plages (the editor still reads it).
     */
    @JsonProperty("indisponibilites")
    public List<PlageStand> getIndisponibilites() {
        return filter(plages, ModeHoraire.FERMETURE);
    }

    /** Replaces every closure of the stand by {@code fermetures}, whatever their own mode says. */
    @JsonProperty("indisponibilites")
    public void setIndisponibilites(List<PlageStand> fermetures) {
        replace(ModeHoraire.FERMETURE, fermetures);
    }

    /** The openings, as a read-only view of {@link #getPlages()} — serialized as {@code ouvertures}, like the closures. */
    @JsonProperty("ouvertures")
    public List<PlageStand> getOuvertures() {
        return filter(plages, ModeHoraire.OUVERTURE);
    }

    /** Replaces every opening of the stand by {@code ouvertures}, whatever their own mode says. */
    @JsonProperty("ouvertures")
    public void setOuvertures(List<PlageStand> ouvertures) {
        replace(ModeHoraire.OUVERTURE, ouvertures);
    }

    /**
     * Always empty: the recurring rules of 3.x are not stored any more. The key stays on the
     * payload so the editor of the previous release keeps working until the plages editor
     * replaces it (ADR 0104).
     */
    @JsonProperty("horaires")
    public List<Object> getHoraires() {
        return List.of();
    }

    /**
     * Rules sent by that editor: kept aside, then expanded into plages over the edition's
     * days by {@code StandService} before the stand is validated — never dropped in silence.
     */
    @JsonProperty("horaires")
    public void setHoraires(List<HorairesRecurrents.Regle> horaires) {
        this.reglesRecues = horaires != null ? List.copyOf(horaires) : List.of();
    }

    /** The rules {@link #setHoraires} received and nothing has laid down yet; reading them clears them. */
    @JsonIgnore
    public List<HorairesRecurrents.Regle> takeReglesRecues() {
        List<HorairesRecurrents.Regle> regles = reglesRecues;
        reglesRecues = List.of();
        return regles;
    }

    private void replace(ModeHoraire mode, List<PlageStand> remplacantes) {
        List<PlageStand> autres =
                new ArrayList<>(plages.stream().filter(p -> p.getMode() != mode).toList());
        if (remplacantes != null) {
            for (PlageStand plage : remplacantes) {
                plage.setMode(mode);
                autres.add(plage);
            }
        }
        plages = autres;
    }

    private static List<PlageStand> filter(List<PlageStand> source, ModeHoraire mode) {
        return source.stream().filter(p -> p.getMode() == mode).toList();
    }

    /**
     * Records what a consigne made of this stand's plages, for the event's
     * days; passing {@code null} reverts to the stored plages.
     */
    @JsonIgnore
    public void setPlagesEffectives(List<PlageStand> plagesEffectives) {
        this.plagesEffectives = plagesEffectives;
    }

    /**
     * The plages to actually reason about: the resolved ones when
     * {@link #setPlagesEffectives} has run, the stored ones otherwise. Falling
     * back keeps every caller — and every test — that never resolves anything
     * behaving as it did before consignes existed.
     *
     * <p>{@code @JsonIgnore} because this is a derived view: the REST payload
     * carries the stored plages, which is what the UI edits.</p>
     */
    @JsonIgnore
    public List<PlageStand> getPlagesEffectives() {
        return plagesEffectives != null ? plagesEffectives : plages;
    }

    /** Closure windows to actually reason about — a view of {@link #getPlagesEffectives()}. */
    @JsonIgnore
    public List<PlageStand> getIndisponibilitesEffectives() {
        return filter(getPlagesEffectives(), ModeHoraire.FERMETURE);
    }

    /** Opening windows to actually reason about — a view of {@link #getPlagesEffectives()}. */
    @JsonIgnore
    public List<PlageStand> getOuverturesEffectives() {
        return filter(getPlagesEffectives(), ModeHoraire.OUVERTURE);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Stand stand)) {
            return false;
        }
        return Objects.equals(id, stand.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
