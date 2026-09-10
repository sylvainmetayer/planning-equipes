package dev.sylvain.planning.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.sql.PreparedStatement;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.Test;

/**
 * A thread that left its request behind must not be told an edition it never
 * designated.
 *
 * <p>{@code editionIdCourant()} used to answer the default edition in that
 * case, silently. Every unwrapped thread hop — a {@code Multi.emitOn}, a
 * {@code CompletableFuture}, a parallel stream — therefore read and
 * <b>wrote</b> into whichever edition happened to be flagged default, whatever
 * the caller was working on. That is the bug {@code JdbcEditionScope} tells the
 * story of in its own javadoc, and the reason a silent default cannot be
 * trusted with a write.</p>
 *
 * <p>A jump to another thread is the shortest way to reproduce it: the CDI
 * request context does not follow, which is exactly what happens to the
 * unwrapped background work this guards against.</p>
 */
@QuarkusTest
class EditionContextFailClosedTest {

    @Inject
    EditionContext editionContext;

    @Inject
    JdbcEditionScope editionScope;

    @Test
    void horsRequeteEtHorsExecuteInLAppelEchoue() {
        assertThatThrownBy(() -> onAnotherThread(() -> editionContext.editionIdCourant()))
                .cause()
                .isInstanceOf(IllegalStateException.class)
                // The message has to say what to do, not just that something is
                // missing: whoever hits this is looking at a stack trace with no
                // edition in it.
                .hasMessageContaining("executeIn");
    }

    @Test
    void enveloppeDansExecuteInLeMemeThreadRepond() throws Exception {
        String edition = editionContext.editionIdCourant();

        String vu = onAnotherThread(() -> editionContext.executeIn(edition, () -> editionContext.editionIdCourant()));

        assertThat(vu).isEqualTo(edition);
    }

    /**
     * Inside its request the resolution is unchanged, default fallback included:
     * a client naming no edition, or one naming an edition since deleted, is the
     * ordinary case and must keep working.
     */
    @Test
    void dansLaRequeteLaResolutionEstInchangee() {
        assertThat(editionContext.editionIdCourant()).isNotBlank();
    }

    /**
     * The fault this guard exists to stop is a <b>write</b> landing in the
     * wrong edition, and that is what this pins.
     *
     * <p>The tests above cover the read. Before the fix, this same statement ran
     * to completion off-request: {@code JdbcEditionScope} asked for the current
     * edition, was handed the default one, and the {@code DELETE} was applied
     * against it — silently, in whatever edition happened to be flagged default.
     * Now the scope refuses to build the statement at all, and the transaction
     * rolls back.</p>
     */
    @Test
    void offRequestAWriteIsRefusedRatherThanAppliedToTheDefaultEdition() {
        assertThatThrownBy(() ->
                        onAnotherThread(() -> editionScope.writeAndReturn("suppression de test", connection -> {
                            try (PreparedStatement ps = editionScope.prepareScoped(
                                    connection, "DELETE FROM stand WHERE edition_id = ? AND id = ?")) {
                                ps.setString(2, "ce-stand-n-existe-pas");
                                return ps.executeUpdate();
                            }
                        })))
                .cause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("executeIn");
    }

    private static <T> T onAnotherThread(java.util.function.Supplier<T> travail) throws Exception {
        try {
            return CompletableFuture.supplyAsync(travail).get();
        } catch (ExecutionException e) {
            // Unwrapped by the assertions above, which look at the cause.
            throw new IllegalStateException(e.getCause());
        }
    }
}
