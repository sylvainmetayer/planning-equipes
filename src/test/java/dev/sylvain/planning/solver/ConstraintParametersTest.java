package dev.sylvain.planning.solver;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.domain.ParametresLegaux;
import dev.sylvain.planning.domain.ParametresQualite;
import dev.sylvain.planning.solver.ConstraintParameters.ConstraintParameter;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * What the Contraintes screen shows beside a rule: the value, and the field it
 * comes from. The formatting is the point — a week shown as « 2880 min » is a
 * number nobody reads as forty-eight hours.
 */
class ConstraintParametersTest {

    @Test
    void theConsecutiveDaysCeilingIsShownWithTheFormThatHoldsIt() {
        ParametresQualite qualite = new ParametresQualite();

        List<ConstraintParameter> parametres =
                ConstraintParameters.of("maxJoursConsecutifsTravailles", new ParametresLegaux(), qualite);

        assertThat(parametres).singleElement().satisfies(parametre -> {
            assertThat(parametre.libelle()).isEqualTo("Jours travaillés d'affilée");
            assertThat(parametre.valeur()).isEqualTo("8 jours");
            assertThat(parametre.lien()).isEqualTo("/parametres");
            assertThat(parametre.onglet()).isEqualTo("edition");
        });
    }

    @Test
    void bothFormsOfTheCeilingPointAtTheSameField() {
        ParametresLegaux legaux = new ParametresLegaux();
        ParametresQualite qualite = new ParametresQualite();

        assertThat(ConstraintParameters.of("maxJoursConsecutifsTravaillesDur", legaux, qualite))
                .isEqualTo(ConstraintParameters.of("maxJoursConsecutifsTravailles", legaux, qualite));
    }

    @Test
    void aWeeklyCapCarriesWhatMeasuresTheWeekAndNotOnlyItsCeiling() {
        List<ConstraintParameter> parametres =
                ConstraintParameters.of("dureeHebdomadaireMax", new ParametresLegaux(), new ParametresQualite());

        // The ceiling first, then what decides how much of the week is work:
        // an organiser reading « 48 h » alone cannot explain why five days of
        // 10 h amplitude pass.
        assertThat(parametres)
                .extracting(ConstraintParameter::libelle)
                .containsExactly("Durée hebdomadaire maximale, majeurs", "Durée de la pause légale");
        assertThat(parametres).first().extracting(ConstraintParameter::valeur).isEqualTo("48 h");
    }

    @Test
    void aRuleThatReadsNoSettingCarriesNone() {
        assertThat(ConstraintParameters.of("posteDoitEtrePourvu", new ParametresLegaux(), new ParametresQualite()))
                .isEmpty();
        assertThat(ConstraintParameters.of("inconnue", new ParametresLegaux(), new ParametresQualite()))
                .isEmpty();
    }

    @Test
    void aDurationIsReadAsAHumanWritesIt() {
        ParametresLegaux legaux = new ParametresLegaux();
        legaux.setDureeHebdomadaireMaxMinutes(44 * 60 + 30);
        legaux.setDureePauseMinutes(45);

        assertThat(valeur(ConstraintParameters.of("dureeHebdomadaireMax", legaux, new ParametresQualite()), 0))
                .isEqualTo("44 h 30");
        assertThat(valeur(ConstraintParameters.of("travailContinuMaxMajeur", legaux, new ParametresQualite()), 0))
                .isEqualTo("45 min");
    }

    /**
     * The break the daily cap deducts is the one the Contraintes screen shows
     * beside it. It used to be shown as a « oui / non » — whether the organiser
     * had declared the break taken on the post — beside the duration; there is
     * no mode left to declare (ADR 0048), so the duration is the whole answer.
     */
    @Test
    void theDailyCapShowsTheBreakItDeducts() {
        ParametresLegaux legaux = new ParametresLegaux();
        legaux.setDureePauseMinutes(30);
        assertThat(valeur(ConstraintParameters.of("dureeQuotidienneMaxMajeur", legaux, new ParametresQualite()), 0))
                .isEqualTo("30 min");
    }

    @Test
    void aMealWindowIsShownAsTheRangeItIs() {
        ParametresLegaux legaux = new ParametresLegaux();
        legaux.setCoupureRepasMidiDebut(LocalTime.of(11, 30));
        legaux.setCoupureRepasMidiFin(LocalTime.of(14, 0));

        assertThat(valeur(ConstraintParameters.of("coupureRepasObligatoire", legaux, new ParametresQualite()), 1))
                .isEqualTo("11:30 – 14:00");
    }

    @Test
    void anUnsetHourSaysSoRatherThanShowingNothing() {
        ParametresQualite sansHeure = new ParametresQualite(
                2, null, LocalTime.of(8, 0), 660, 2, ParametresQualite.JOURS_CONSECUTIFS_MAX_PAR_DEFAUT);

        assertThat(valeur(
                        ConstraintParameters.of("eviterFermeturePuisOuverture", new ParametresLegaux(), sansHeure), 0))
                .isEqualTo("non réglée");
    }

    /**
     * The resource asks for the parameters of every rule on every read of the
     * screen, including before an edition has stored anything.
     */
    @Test
    void nothingStoredYetIsAnEmptyListRatherThanAFailure() {
        assertThat(ConstraintParameters.of("maxJoursConsecutifsTravailles", null, null))
                .isEmpty();
    }

    private static String valeur(List<ConstraintParameter> parametres, int index) {
        return parametres.get(index).valeur();
    }
}
