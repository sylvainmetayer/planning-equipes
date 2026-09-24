package dev.sylvain.planning.service.backup;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.mailer.MockMailbox;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A backup configured with {@code MAIL_ADMIN} left empty — the documented way
 * to switch the admin notifications off: a failed night mails nobody, and the
 * screen says so instead.
 */
@QuarkusTest
@TestProfile(BackupWithoutAlertRecipientTest.Profile.class)
class BackupWithoutAlertRecipientTest {

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "planning.backup.directory", BackupServiceTest.DIRECTORY.toString(), "planning.mail.admin", "");
        }

        @Override
        public Set<Class<?>> getEnabledAlternatives() {
            return Set.of(BackupServiceTest.PgDumpStub.class);
        }
    }

    @Inject
    BackupService backupService;

    @Inject
    BackupRepository repository;

    @Inject
    MockMailbox mailbox;

    @BeforeEach
    void reset() {
        mailbox.clear();
        repository.setActive(true);
        repository.saveLastRun(BackupRun.never());
        BackupServiceTest.dumpFails = true;
    }

    @AfterEach
    void restore() {
        BackupServiceTest.dumpFails = false;
    }

    @Test
    void aFailedNightMailsNobodyAndTheScreenSaysSo() {
        backupService.scheduledBackup();

        assertThat(repository.lastRun().succeeded()).isFalse();
        assertThat(mailbox.getTotalMessagesSent()).isZero();
        assertThat(backupService.state().alertRecipientMissing()).isTrue();
    }
}
