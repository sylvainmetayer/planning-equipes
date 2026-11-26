package dev.sylvain.planning.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

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
    /** The deprecated per-mode lists a body carried, re-applied over {@code plages} whichever key came first. */
    @JsonIgnore
    private final Map<ModeHoraire, List<PlageStand>> legacy = new EnumMap<>(ModeHoraire.class);
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
     * The stand's <b>model</b> (ADR 0104): recurring rules such as « Saturday and Sunday 10:00-18:00 »,
     * kept while the edition has no day to lay them on and unfolded into {@link #plages} as days
     * appear. {@code null} means « not sent »: a write carrying no {@code horaires} leaves the stored
     * rules alone, where an empty list clears them. Resolution never reads it, only plages.
     */
    private List<HorairesRecurrents.Regle> horaires;

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
    @JsonProperty("plages")
    @Schema(readOnly = false)
    public List<PlageStand> getPlages() {
        return plages;
    }

    @JsonIgnore
    public void setPlages(List<PlageStand> plages) {
        this.plages = plages != null ? plages : new ArrayList<>();
    }

    /**
     * The {@code plages} key of a body. A deprecated {@code ouvertures} / {@code indisponibilites} key
     * in the same body still wins for its mode, whatever the order of the keys: an editor of the 3.x
     * sends back the {@code plages} it read next to the lists it changed.
     */
    @JsonProperty("plages")
    public void receivePlages(List<PlageStand> plages) {
        setPlages(plages);
        legacy.forEach(this::replace);
    }

    /**
     * The closures, as a read-only view of {@link #getPlages()}.
     *
     * @deprecated read {@code plages} (mode {@code FERMETURE}); kept on the wire until 5.0 for the clients of 3.x
     */
    @Deprecated(since = "4.0")
    @JsonProperty("indisponibilites")
    public List<PlageStand> getIndisponibilites() {
        return filter(plages, ModeHoraire.FERMETURE);
    }

    /**
     * Replaces every closure of the stand by {@code fermetures}, whatever their own mode says.
     *
     * @deprecated write {@code plages}; accepted until 5.0 for the clients of 3.x
     */
    @Deprecated(since = "4.0")
    @JsonProperty("indisponibilites")
    public void setIndisponibilites(List<PlageStand> fermetures) {
        legacy.put(ModeHoraire.FERMETURE, fermetures);
        replace(ModeHoraire.FERMETURE, fermetures);
    }

    /**
     * The openings, as a read-only view of {@link #getPlages()}.
     *
     * @deprecated read {@code plages} (mode {@code OUVERTURE}); kept on the wire until 5.0 for the clients of 3.x
     */
    @Deprecated(since = "4.0")
    @JsonProperty("ouvertures")
    public List<PlageStand> getOuvertures() {
        return filter(plages, ModeHoraire.OUVERTURE);
    }

    /**
     * Replaces every opening of the stand by {@code ouvertures}, whatever their own mode says.
     *
     * @deprecated write {@code plages}; accepted until 5.0 for the clients of 3.x
     */
    @Deprecated(since = "4.0")
    @JsonProperty("ouvertures")
    public void setOuvertures(List<PlageStand> ouvertures) {
        legacy.put(ModeHoraire.OUVERTURE, ouvertures);
        replace(ModeHoraire.OUVERTURE, ouvertures);
    }

    /** The stand's model: the rules it declares, never the plages they produced. */
    @JsonProperty("horaires")
    public List<HorairesRecurrents.Regle> getHoraires() {
        return horaires != null ? horaires : List.of();
    }

    /**
     * Sets the model. The server stores it as it is while the edition has no day, and unfolds it
     * into plages over the days it has otherwise (see {@code StandService}).
     */
    @JsonProperty("horaires")
    public void setHoraires(List<HorairesRecurrents.Regle> horaires) {
        this.horaires = horaires != null ? List.copyOf(horaires) : null;
    }

    /** The rules a write sent, {@code null} when it sent none — the difference between « keep » and « clear ». */
    @JsonIgnore
    public List<HorairesRecurrents.Regle> horairesEnvoyes() {
        return horaires;
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
