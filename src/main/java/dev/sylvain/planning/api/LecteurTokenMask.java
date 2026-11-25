package dev.sylvain.planning.api;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationConfig;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.BeanPropertyWriter;
import com.fasterxml.jackson.databind.ser.BeanSerializerModifier;
import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.service.EditionContext;
import io.quarkus.jackson.ObjectMapperCustomizer;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.List;

/**
 * An animateur's espace token never reaches a lecteur (ADR 0099): whoever holds it opens that animateur's
 * espace, which is a door to write through. Every response serialises the {@link Animateur} with its
 * {@code accessToken} as {@code null} when the caller only reads the edition of the request — the list, the
 * plan, the snapshots, a job's result, and the PDF rendered from what the lecteur sends back, which then
 * carries no espace link. The key stays, so the JSON contract does not move.
 *
 * <p>At the serialiser rather than in each view: a new route that returns an animateur is covered without
 * anybody remembering. Off a request ({@link EditionContext#callerReadsOnly}) nothing is masked, so a job,
 * a snapshot or a solve exchange keeps the token.</p>
 */
// @Singleton, as Quarkus documents for an ObjectMapperCustomizer: it runs while the mapper is built.
@Singleton
public class LecteurTokenMask implements ObjectMapperCustomizer {

    static final String PROPERTY = "accessToken";

    private final EditionContext editionContext;

    @Inject
    public LecteurTokenMask(EditionContext editionContext) {
        this.editionContext = editionContext;
    }

    @Override
    public void customize(ObjectMapper mapper) {
        mapper.registerModule(new SimpleModule("lecteur-token-mask")
                .setSerializerModifier(new BeanSerializerModifier() {
                    @Override
                    public List<BeanPropertyWriter> changeProperties(
                            SerializationConfig config, BeanDescription description, List<BeanPropertyWriter> writers) {
                        if (!Animateur.class.isAssignableFrom(description.getBeanClass())) {
                            return writers;
                        }
                        // In place: Jackson filters this list afterwards (a mix-in's include list), so it stays
                        // mutable.
                        writers.replaceAll(w -> PROPERTY.equals(w.getName()) ? new Masked(w) : w);
                        return writers;
                    }
                }));
    }

    private final class Masked extends BeanPropertyWriter {

        Masked(BeanPropertyWriter base) {
            super(base);
        }

        @Override
        public void serializeAsField(Object bean, JsonGenerator gen, SerializerProvider prov) throws Exception {
            if (editionContext.callerReadsOnly()) {
                gen.writeNullField(getName());
            } else {
                super.serializeAsField(bean, gen, prov);
            }
        }
    }
}
