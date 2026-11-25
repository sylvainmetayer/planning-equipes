package dev.sylvain.planning.service.rh;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.sylvain.planning.service.BusinessError;
import org.junit.jupiter.api.Test;

class ContactsServiceTest {

    @Test
    void blanksBecomeNothingAndValuesAreTrimmed() {
        ContactsAnimateur normalized =
                ContactsService.normalize(new ContactsAnimateur("  Camille ", " +33 612 345 678 ", "   ", "", null));

        assertThat(normalized.urgenceNom()).isEqualTo("Camille");
        assertThat(normalized.urgenceTelephone()).isEqualTo("+33 612 345 678");
        assertThat(normalized.representantNom()).isNull();
        assertThat(normalized.representantTelephone()).isNull();
        assertThat(normalized.hasRepresentative()).isFalse();
        assertThat(ContactsService.normalize(null).isEmpty()).isTrue();
        assertThat(ContactsService.normalize(new ContactsAnimateur(" ", null, "", " ", null))
                        .isEmpty())
                .isTrue();
    }

    @Test
    void aRepresentativeIsKnownByANameOrANumber() {
        assertThat(new ContactsAnimateur(null, null, "Dominique", null, null).hasRepresentative())
                .isTrue();
        assertThat(new ContactsAnimateur(null, null, null, "0123456789", null).hasRepresentative())
                .isTrue();
        assertThat(new ContactsAnimateur("Camille", "0123456789", null, null, null).hasRepresentative())
                .isFalse();
    }

    @Test
    void aBadNumberOrALongNameIsRefused() {
        assertThatThrownBy(() -> ContactsService.normalize(new ContactsAnimateur(null, "12", null, null, null)))
                .isInstanceOf(BusinessError.Invalid.class);
        assertThatThrownBy(
                        () -> ContactsService.normalize(new ContactsAnimateur(null, "abc def ghi", null, null, null)))
                .isInstanceOf(BusinessError.Invalid.class);
        assertThatThrownBy(
                        () -> ContactsService.normalize(new ContactsAnimateur(null, "1".repeat(31), null, null, null)))
                .isInstanceOf(BusinessError.Invalid.class);
        assertThatThrownBy(
                        () -> ContactsService.normalize(new ContactsAnimateur("x".repeat(101), null, null, null, null)))
                .isInstanceOf(BusinessError.Invalid.class);
    }
}
