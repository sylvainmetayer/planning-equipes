package dev.sylvain.planning.service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import ai.timefold.solver.core.api.solver.Solver;
import ai.timefold.solver.core.api.solver.SolverFactory;
import ai.timefold.solver.core.config.solver.termination.TerminationConfig;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import ai.timefold.solver.core.config.solver.SolverConfig;
import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.ContactLegal;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.NiveauCompetence;
import dev.sylvain.planning.domain.PlanningFestival;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.domain.StatutAnimateur;
import dev.sylvain.planning.domain.TypologieJeu;

@ApplicationScoped
public class PlanningService {

    private final SolverFactory<PlanningFestival> solverFactory;

    public PlanningService(
            @ConfigProperty(name = "planning.solver.seconds-limit", defaultValue = "30") Long secondsLimit) {
        SolverConfig solverConfig = SolverConfig.createFromXmlResource("solver/solverConfig.xml");
        if (solverConfig.getTerminationConfig() == null) {
            solverConfig.setTerminationConfig(new TerminationConfig());
        }
        solverConfig.getTerminationConfig().setSecondsSpentLimit(secondsLimit);
        this.solverFactory = SolverFactory.create(solverConfig);
    }

    public PlanningFestival construireExemple() {
        LocalDate debutFestival = LocalDate.now().plusDays(7);

        Creneau creneauMatin = new Creneau("J1-MATIN", 1, debutFestival, LocalTime.of(9, 0), LocalTime.of(13, 0));
        Creneau creneauApresMidi = new Creneau("J1-AM", 1, debutFestival, LocalTime.of(14, 0), LocalTime.of(18, 0));

        Stand standStrategie = new Stand("STAND-STRAT", "Stand stratégie", Set.of(TypologieJeu.STRATEGIE), 1, 1, false);

        Animateur referent = new Animateur("A1", "Alice", "Referente", LocalDate.now().minusYears(24), StatutAnimateur.BENEVOLE);
        referent.setCompetences(Map.of(TypologieJeu.STRATEGIE, NiveauCompetence.REFERENT));
        referent.setDisponibilites(Set.of(creneauMatin, creneauApresMidi));

        Animateur autonome = new Animateur("A2", "Bruno", "Autonome", LocalDate.now().minusYears(19), StatutAnimateur.BENEVOLE);
        autonome.setCompetences(Map.of(TypologieJeu.STRATEGIE, NiveauCompetence.AUTONOME));
        autonome.setDisponibilites(Set.of(creneauMatin, creneauApresMidi));

        Animateur mineur = new Animateur("A3", "Chloe", "Junior", LocalDate.now().minusYears(16), StatutAnimateur.BENEVOLE);
        mineur.setCompetences(Map.of(TypologieJeu.STRATEGIE, NiveauCompetence.DEBUTANT));
        mineur.setDisponibilites(Set.of(creneauApresMidi));
        mineur.setContactLegal(new ContactLegal("Parent Junior", "+33000000000", "parent@example.org"));

        List<PosteAffectation> postes = List.of(
                new PosteAffectation("P1", standStrategie, creneauMatin),
                new PosteAffectation("P2", standStrategie, creneauApresMidi));

        return new PlanningFestival(debutFestival, List.of(referent, autonome, mineur), postes);
    }

    public PlanningFestival resoudre(PlanningFestival problem) {
        Solver<PlanningFestival> solver = solverFactory.buildSolver();
        return solver.solve(problem);
    }
}
