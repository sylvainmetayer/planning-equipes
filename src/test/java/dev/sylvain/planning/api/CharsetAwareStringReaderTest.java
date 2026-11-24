package dev.sylvain.planning.api;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.ws.rs.core.MediaType;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CharsetAwareStringReaderTest {

    private static final String NAME = "Édition accentuée";
    private final CharsetAwareStringReader reader = new CharsetAwareStringReader();

    private String read(byte[] body, MediaType mediaType) throws IOException {
        return reader.readFrom(String.class, String.class, null, mediaType, null, new ByteArrayInputStream(body));
    }

    @Test
    void readsOnlyStrings() {
        assertThat(reader.isReadable(String.class, String.class, null, MediaType.TEXT_PLAIN_TYPE))
                .isTrue();
        assertThat(reader.isReadable(Object.class, Object.class, null, MediaType.TEXT_PLAIN_TYPE))
                .isFalse();
    }

    @Test
    void decodesInTheDeclaredCharset() throws IOException {
        MediaType latin1 = new MediaType("application", "x-yaml", Map.of("charset", "ISO-8859-1"));
        assertThat(read(NAME.getBytes(StandardCharsets.ISO_8859_1), latin1)).isEqualTo(NAME);
    }

    @Test
    void fallsBackToUtf8WithoutCharsetOrMediaType() throws IOException {
        byte[] utf8 = NAME.getBytes(StandardCharsets.UTF_8);
        assertThat(read(utf8, new MediaType("application", "x-yaml"))).isEqualTo(NAME);
        assertThat(read(utf8, null)).isEqualTo(NAME);
    }

    @Test
    void fallsBackToUtf8OnAnUnknownOrIllegalCharset() throws IOException {
        byte[] utf8 = NAME.getBytes(StandardCharsets.UTF_8);
        assertThat(read(utf8, new MediaType("text", "plain", Map.of("charset", "no-such-charset"))))
                .isEqualTo(NAME);
        assertThat(read(utf8, new MediaType("text", "plain", Map.of("charset", "bad name!"))))
                .isEqualTo(NAME);
    }
}
