package dev.sylvain.planning.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.service.edition.RequiresActiveEdition;
import dev.sylvain.planning.service.espace.DeclarationDisponibiliteRepository;
import dev.sylvain.planning.service.espace.DeclarationDisponibiliteService;
import dev.sylvain.planning.service.publication.MailService;
import dev.sylvain.planning.service.publication.PlanPublicationService;
import dev.sylvain.planning.service.publication.PlanningDeliveryService;
import dev.sylvain.planning.service.publication.RelanceManuelleService;
import io.quarkiverse.mcp.server.Tool;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Only the active edition reaches outside (ADR 0072), and nothing may opt out
 * of that by omission.
 *
 * <p>Fails on a mail of {@code MailService} that is neither guarded by
 * {@link RequiresActiveEdition} nor argued here, and on an MCP tool that
 * leaves the application ({@code openWorldHint = true}) whose service method
 * is neither guarded nor argued. The best-effort notifications are held
 * elsewhere: {@code NotificationDispatcher.editionScoped} is a
 * {@code switch} without {@code default}, so a new case does not compile
 * until somebody says whether it belongs to an edition.</p>
 */
class ActiveEditionEmissionStructuralTest {

    /** Mails that belong to the instance rather than to an edition, with the reason. */
    private static final Map<String, String> INSTANCE_MAILS = Map.of(
            "sendTestMail",
            "the Débogage test mail checks the SMTP relay of the instance and goes to the admin address only");

    /**
     * The service method each outward MCP tool goes through. A new outward
     * tool fails until it is added here, which is the moment to guard it.
     */
    private static final Map<String, Method> OUTWARD_TOOLS = Map.of(
            "publier_planning",
            method(PlanPublicationService.class, "publier", List.class, List.class),
            "envoyer_planning_animateur",
            method(PlanningDeliveryService.class, "sendToOneAnimateur", String.class),
            "relancer_animateurs",
            method(RelanceManuelleService.class, "relancer", List.class),
            "configurer_collecte_disponibilites",
            method(
                    DeclarationDisponibiliteService.class,
                    "configure",
                    DeclarationDisponibiliteRepository.FenetreCollecte.class,
                    boolean.class));

    /**
     * Outward service methods that refuse only in one branch, hence call the
     * guard explicitly rather than carry the annotation — with the reason.
     */
    private static final Map<String, String> CONDITIONAL_GUARDS = Map.of(
            "DeclarationDisponibiliteService#configure",
            "opening the collection window of an edition being prepared is allowed; only the invitation mail"
                    + " (prevenirAnimateurs) is refused, through RequiresActiveEditionInterceptor.refuseIfInactive");

    @Test
    void everyMailToAnAnimateurRequiresTheActiveEdition() {
        List<String> unguarded = Arrays.stream(MailService.class.getDeclaredMethods())
                .filter(m -> Modifier.isPublic(m.getModifiers()) && m.getName().startsWith("send"))
                .filter(m -> !m.isAnnotationPresent(RequiresActiveEdition.class))
                .map(Method::getName)
                .filter(name -> !INSTANCE_MAILS.containsKey(name))
                .toList();
        assertThat(unguarded)
                .as("mails of MailService neither guarded by @RequiresActiveEdition nor argued in INSTANCE_MAILS")
                .isEmpty();
    }

    @Test
    void everyOutwardToolGoesThroughAGuardedServiceMethod() throws Exception {
        Set<String> outward = OutilsMcp.all().stream()
                .filter(outil -> outil.getAnnotation(Tool.class).annotations().openWorldHint())
                .map(FeatureNames::of)
                .collect(Collectors.toSet());
        assertThat(OUTWARD_TOOLS.keySet())
                .as("every tool with openWorldHint = true names the service method it goes through")
                .containsExactlyInAnyOrderElementsOf(outward);

        OUTWARD_TOOLS.values().forEach(service -> {
            String key = service.getDeclaringClass().getSimpleName() + "#" + service.getName();
            assertThat(service.isAnnotationPresent(RequiresActiveEdition.class) || CONDITIONAL_GUARDS.containsKey(key))
                    .as("%s must carry @RequiresActiveEdition, or be argued in CONDITIONAL_GUARDS", key)
                    .isTrue();
        });
    }

    private static Method method(Class<?> owner, String name, Class<?>... parameters) {
        try {
            return owner.getDeclaredMethod(name, parameters);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(owner.getSimpleName() + "#" + name + " moved: update this test", e);
        }
    }
}
