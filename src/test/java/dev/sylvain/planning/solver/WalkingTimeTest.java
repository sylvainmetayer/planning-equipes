package dev.sylvain.planning.solver;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.domain.Emplacement;
import org.junit.jupiter.api.Test;

class WalkingTimeTest {

    @Test
    void aKilometreAtFourKmHWithADetourOfOnePointThreeIsTwentyMinutes() {
        assertThat(WalkingTime.minutes(1000.0, 4.0, 1.3)).isEqualTo(20);
    }

    @Test
    void roundsUpToTheMinute() {
        assertThat(WalkingTime.minutes(1000.0, 4.0, 1.0)).isEqualTo(15);
        assertThat(WalkingTime.minutes(1001.0, 4.0, 1.0)).isEqualTo(16);
    }

    @Test
    void anEmplacementWithoutCoordinatesIsAnUnknownTripNotAZeroOne() {
        Emplacement situe = new Emplacement("A", "A", 46.65, 2.25);
        Emplacement sansCoordonnees = new Emplacement("B", "B", null, null);

        assertThat(WalkingTime.minutes(situe, sansCoordonnees, 4.0, 1.3)).isNull();
        assertThat(WalkingTime.minutes(situe, null, 4.0, 1.3)).isNull();
        assertThat(WalkingTime.minutes(situe, situe, 4.0, 1.3)).isZero();
    }

    @Test
    void missingMinutesAreCountedBeyondTheToleranceAndNeverNegative() {
        assertThat(WalkingTime.missingMinutes(20, 10, 5)).isEqualTo(5);
        assertThat(WalkingTime.missingMinutes(20, 30, 5)).isZero();
    }
}
