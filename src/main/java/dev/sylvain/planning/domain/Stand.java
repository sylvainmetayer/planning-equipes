package dev.sylvain.planning.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
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
     * Dated closure <b>exceptions</b> for this stand, e.g. "closed 14:00-16:00
     * on 2026-07-18" — possibly only part of a créneau. Empty = the stand is
     * open on every créneau (the default), unless {@link #ouvertures} or a
     * {@link #horaires} rule says otherwise for that day. See
     * {@link Creneau#segmentsOuvertsMinutes(Stand)}.
     */
    private List<IndisponibiliteStand> indisponibilites = new ArrayList<>();
    /**
     * Dated opening <b>exceptions</b> for this stand — the inverse of
     * {@link #indisponibilites}, for a stand normally closed and only staffed
     * during specific windows. Empty = no day is opening-only (the default). A
     * day can never carry both an entry here and one in
     * {@link #indisponibilites}; see {@link OuvertureStand}'s javadoc for the
     * three-state rule this implies.
     */
    private List<OuvertureStand> ouvertures = new ArrayList<>();
    /**
     * Recurring opening/closing rules — what a stable opening pattern is
     * entered as, instead of one dated row per event day. A dated entry
     * above always wins over these for the day it names; see
     * {@link HoraireStand} for the full layering.
     */
    private List<HoraireStand> horaires = new ArrayList<>();
    /**
     * The dated windows {@link #horaires} expands to for the event's days,
     * merged with the dated exceptions above — {@code null} until
     * {@code HoraireStandResolver} has run against a known set of dates.
     *
     * <p>Kept <b>beside</b> the persisted lists rather than substituted into
     * them on purpose: {@code upsertStand} writes {@link #indisponibilites} /
     * {@link #ouvertures}, so a resolved stand travelling back through a save
     * (which a stand reached through a {@code PlanningEvenement} does) can never
     * silently freeze the expansion into the database as a few hundred dated
     * rows.</p>
     */
    private List<IndisponibiliteStand> indisponibilitesEffectives;

    private List<OuvertureStand> ouverturesEffectives;

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

    /** Editor/publisher-tier stand: high-visibility, needs experienced staffing continuity. */
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

    public List<IndisponibiliteStand> getIndisponibilites() {
        return indisponibilites;
    }

    public void setIndisponibilites(List<IndisponibiliteStand> indisponibilites) {
        this.indisponibilites = indisponibilites != null ? indisponibilites : new ArrayList<>();
    }

    public List<OuvertureStand> getOuvertures() {
        return ouvertures;
    }

    public void setOuvertures(List<OuvertureStand> ouvertures) {
        this.ouvertures = ouvertures != null ? ouvertures : new ArrayList<>();
    }

    public List<HoraireStand> getHoraires() {
        return horaires;
    }

    public void setHoraires(List<HoraireStand> horaires) {
        this.horaires = horaires != null ? horaires : new ArrayList<>();
    }

    /**
     * Records what {@code HoraireStandResolver} resolved this stand's rules and
     * exceptions to, for the event's days. Both lists replace each other
     * wholesale; passing {@code null} for either reverts to the dated lists.
     */
    public void setFenetresEffectives(List<IndisponibiliteStand> fermetures, List<OuvertureStand> ouvertures) {
        this.indisponibilitesEffectives = fermetures;
        this.ouverturesEffectives = ouvertures;
    }

    /**
     * Closure windows to actually reason about: the resolved ones when
     * {@link #setFenetresEffectives} has run, the dated ones otherwise. Falling
     * back keeps every caller — and every test — that never resolves anything
     * behaving exactly as it did before rules existed.
     *
     * <p>{@code @JsonIgnore} because this is a derived view: the REST payload
     * carries the rules and the dated exceptions, which is what the UI edits,
     * and the frontend recomputes the resolution itself for its preview (see
     * {@code core/horaire-stand.ts}).</p>
     */
    @JsonIgnore
    public List<IndisponibiliteStand> getIndisponibilitesEffectives() {
        return indisponibilitesEffectives != null ? indisponibilitesEffectives : indisponibilites;
    }

    /** Opening windows to actually reason about — see {@link #getIndisponibilitesEffectives()}. */
    @JsonIgnore
    public List<OuvertureStand> getOuverturesEffectives() {
        return ouverturesEffectives != null ? ouverturesEffectives : ouvertures;
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
