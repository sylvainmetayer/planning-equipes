package dev.sylvain.planning.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.mcp.DisponibiliteMcpTools.CollecteView;
import dev.sylvain.planning.service.espace.DeclarationDisponibiliteRepository.FenetreCollecte;
import dev.sylvain.planning.service.espace.DeclarationDisponibiliteService;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** {@code configurer_collecte_disponibilites} does what {@code modifier_collecte_disponibilites} does (ADR 0127 § 4). */
class DisponibiliteMcpToolsFormerNamesTest {

    /** Records each window asked for, and whether the invitation was to leave. */
    private static final class CollecteFictive extends DeclarationDisponibiliteService {

        private final List<String> appels = new ArrayList<>();

        CollecteFictive() {
            super(null, null, null, null, null, null, null, null, null);
        }

        @Override
        public ConfigurationAppliquee configure(
                boolean ouverte,
                LocalDate debut,
                LocalDate fin,
                Boolean relanceAutomatique,
                boolean prevenirAnimateurs) {
            appels.add(ouverte + " " + debut + " " + fin + " " + relanceAutomatique + " " + prevenirAnimateurs);
            return new ConfigurationAppliquee(
                    new FenetreCollecte(ouverte, debut, fin, Boolean.TRUE.equals(relanceAutomatique)), null);
        }
    }

    @Test
    void theFormerNameConfiguresTheCollectionLikeTheNewOne() {
        CollecteFictive collecte = new CollecteFictive();
        DisponibiliteMcpTools tools = new DisponibiliteMcpTools(collecte, null, null, null);

        CollecteView nouveau =
                tools.configureAvailabilityCollection(true, "2027-01-04", "2027-01-31", false, true, "E1");
        CollecteView ancien =
                tools.configureAvailabilityCollectionFormerName(true, "2027-01-04", "2027-01-31", false, true, "E1");

        assertThat(ancien).isEqualTo(nouveau);
        assertThat(ancien.ouverte()).isTrue();
        assertThat(ancien.debut()).isEqualTo(LocalDate.of(2027, 1, 4));
        // The invitation flag travels as given: the former name sends exactly what the new one sends.
        assertThat(collecte.appels)
                .containsExactly("true 2027-01-04 2027-01-31 true false", "true 2027-01-04 2027-01-31 true false");
    }
}
