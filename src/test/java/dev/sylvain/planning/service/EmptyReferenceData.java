package dev.sylvain.planning.service;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.ContrainteAdHoc;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.Emplacement;
import dev.sylvain.planning.domain.FenetreRepas;
import dev.sylvain.planning.domain.ParametresLegaux;
import dev.sylvain.planning.domain.ParametresSolveur;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.domain.VerrouillagePlanning;
import dev.sylvain.planning.service.referentiel.HoraireStandResolver;
import dev.sylvain.planning.service.referentiel.ReferenceData;
import dev.sylvain.planning.service.referentiel.TypologieItem;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A referential that holds nothing, for the plain (non-CDI) tests: they build
 * a {@link PlanningService} by hand to exercise the solver on a scenario file
 * or a hand-built problem, and never read the database.
 *
 * <p>It lives in {@code src/test} on purpose. The same behaviour used to be
 * spread across fifteen {@code repository == null ? …} branches inside
 * {@link ReferenceDataService} — production code written for the tests, and
 * unreachable in production except when a wiring failure turned itself into an
 * empty list instead of an error.</p>
 *
 * <p>Default parameters, not nulls: the solver reads them to build its
 * constraints, and the defaults are exactly what an edition that has never
 * been configured would return.</p>
 */
public class EmptyReferenceData implements ReferenceData {

    @Override
    public List<Animateur> listAnimateurs() {
        return List.of();
    }

    @Override
    public List<Stand> listStands() {
        return List.of();
    }

    @Override
    public List<Stand> listSolvedStands() {
        return List.of();
    }

    @Override
    public void resolveHoraires(List<Stand> stands, List<Creneau> creneaux) {
        // No consigne without a database: the rules alone.
        HoraireStandResolver.apply(stands, creneaux);
    }

    @Override
    public List<Creneau> listCreneaux() {
        return List.of();
    }

    @Override
    public List<Emplacement> listEmplacements() {
        return List.of();
    }

    @Override
    public List<TypologieItem> listTypologies() {
        return List.of();
    }

    @Override
    public List<VerrouillagePlanning> listVerrouillages() {
        return List.of();
    }

    @Override
    public List<ContrainteAdHoc> snapshotContraintes() {
        return List.of();
    }

    @Override
    public Set<String> getContraintesDesactivees() {
        return Set.of();
    }

    @Override
    public dev.sylvain.planning.domain.ParametresQualite getParametresQualite() {
        return new dev.sylvain.planning.domain.ParametresQualite();
    }

    @Override
    public java.util.Map<String, Boolean> getEtatsContraintes() {
        return java.util.Map.of();
    }

    @Override
    public Map<String, Integer> getConstraintWeights() {
        return Map.of();
    }

    @Override
    public ParametresLegaux getParametresLegaux() {
        return new ParametresLegaux();
    }

    @Override
    public List<FenetreRepas> fenetresRepas() {
        return FenetreRepas.from(getParametresLegaux());
    }

    @Override
    public ParametresSolveur getParametresSolveur() {
        return new ParametresSolveur();
    }
}
