package fr.festival.planning.service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import ai.timefold.solver.core.api.solver.Solver;
import ai.timefold.solver.core.api.solver.SolverFactory;
import jakarta.enterprise.context.ApplicationScoped;
import fr.festival.planning.domain.Animateur;
import fr.festival.planning.domain.ContactLegal;
import fr.festival.planning.domain.Creneau;
import fr.festival.planning.domain.NiveauCompetence;
import fr.festival.planning.domain.PlanningFestival;
import fr.festival.planning.domain.PosteAffectation;
import fr.festival.planning.domain.Stand;
import fr.festival.planning.domain.StatutAnimateur;
import fr.festival.planning.domain.TypologieJeu;

@ApplicationScoped
public class PlanningService {

    private final SolverFactory<PlanningFestival> solverFactory;

    public PlanningService() {
        this.solverFactory = SolverFactory.createFromXmlResource("solver/solverConfig.xml");
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
