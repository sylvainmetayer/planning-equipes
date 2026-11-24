package dev.sylvain.planning.api;

import jakarta.annotation.Priority;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.ext.MessageBodyReader;
import jakarta.ws.rs.ext.Provider;
import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Reads a {@code String} body in the charset the client declares, UTF-8 when it
 * declares none. The stock reader decodes every text body as UTF-8 whatever the
 * {@code Content-Type} says, so a client sending {@code charset=ISO-8859-1}
 * (REST-assured does by default) had its accents turned into replacement
 * characters — an animateur or edition name imported from a scenario file came
 * back damaged.
 */
@Provider
@Consumes(MediaType.WILDCARD)
@Priority(Priorities.USER)
public class CharsetAwareStringReader implements MessageBodyReader<String> {

    @Override
    public boolean isReadable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
        return type == String.class;
    }

    @Override
    public String readFrom(
            Class<String> type,
            Type genericType,
            Annotation[] annotations,
            MediaType mediaType,
            MultivaluedMap<String, String> httpHeaders,
            InputStream entityStream)
            throws IOException {
        return new String(entityStream.readAllBytes(), charsetOf(mediaType));
    }

    private static Charset charsetOf(MediaType mediaType) {
        String name = mediaType == null ? null : mediaType.getParameters().get(MediaType.CHARSET_PARAMETER);
        try {
            return name == null ? StandardCharsets.UTF_8 : Charset.forName(name);
        } catch (IllegalArgumentException _) {
            return StandardCharsets.UTF_8;
        }
    }
}
