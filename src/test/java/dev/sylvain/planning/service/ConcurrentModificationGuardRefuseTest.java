package dev.sylvain.planning.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** The creation half of {@link ConcurrentModificationGuard#refuse}, which needs no database. */
class ConcurrentModificationGuardRefuseTest {

    @Test
    void aTakenIdOnACreationIsAConflictNamingTheId() {
        ConcurrentModificationGuard guard = new ConcurrentModificationGuard(null);

        assertThatThrownBy(() -> guard.refuse(true, "stand", "S1"))
                .isInstanceOf(BusinessError.Conflict.class)
                .hasMessageContaining("« S1 » est déjà pris");
    }
}
