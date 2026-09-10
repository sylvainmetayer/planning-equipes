package dev.sylvain.planning.service.backup;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The retention decides what gets <b>deleted</b>. A value outside its bounds is
 * refused at boot rather than clamped: an operator who asked for thirty copies
 * and silently got ten finds out three weeks later, looking for one that was
 * never kept.
 */
class BackupConfigurationTest {

    @ParameterizedTest
    @ValueSource(ints = {1, 10, 30})
    void acceptsARetentionInsideItsBounds(int retention) {
        assertThatCode(() -> BackupConfiguration.checkRetention(retention)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 31, 365})
    void refusesARetentionOutsideItsBounds(int retention) {
        assertThatThrownBy(() -> BackupConfiguration.checkRetention(retention))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BACKUP_RETENTION");
    }
}
