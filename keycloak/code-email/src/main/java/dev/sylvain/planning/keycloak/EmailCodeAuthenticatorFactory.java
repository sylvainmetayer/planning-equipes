package dev.sylvain.planning.keycloak;

import java.util.List;
import org.keycloak.Config;
import org.keycloak.authentication.Authenticator;
import org.keycloak.authentication.AuthenticatorFactory;
import org.keycloak.models.AuthenticationExecutionModel.Requirement;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.provider.ProviderConfigProperty;

/**
 * Declares the authenticator to Keycloak: the identifier a flow names, the
 * label the admin console shows, and what may be tuned without rebuilding.
 *
 * <p>{@link #getId()} is the contract with {@code realm-planning.json} and with
 * {@code terraform/keycloak/main.tf} — both name {@code planning-code-email}
 * in a flow execution. Rename it here and the realm import fails outright, which is the
 * good failure: an authenticator silently absent would leave the method offered
 * on screen and dead on use.</p>
 */
public class EmailCodeAuthenticatorFactory implements AuthenticatorFactory {

    public static final String ID = "planning-code-email";

    private static final EmailCodeAuthenticator INSTANCE = new EmailCodeAuthenticator();

    /**
     * {@code REQUIRED} and {@code DISABLED} only. {@code ALTERNATIVE} is
     * deliberately absent from this list: the choice between methods is made one
     * level up, by the sub-flow that holds them, so an execution that could make
     * itself optional here would let a login skip the only step it had.
     */
    private static final Requirement[] EXIGENCES = {
        Requirement.REQUIRED, Requirement.DISABLED,
    };

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getDisplayType() {
        return "Code par e-mail (Planning Équipes)";
    }

    @Override
    public String getHelpText() {
        return "Envoie un code à six chiffres à l'adresse du compte, puis le demande. "
                + "Ne réclame aucun enrôlement préalable : c'est la voie de secours "
                + "quand un passkey est perdu et qu'il ne reste aucun code de secours.";
    }

    @Override
    public String getReferenceCategory() {
        return "code-email";
    }

    @Override
    public boolean isConfigurable() {
        return true;
    }

    @Override
    public boolean isUserSetupAllowed() {
        return false;
    }

    @Override
    public Requirement[] getRequirementChoices() {
        return EXIGENCES.clone();
    }

    @Override
    public List<ProviderConfigProperty> getConfigProperties() {
        ProviderConfigProperty validite = new ProviderConfigProperty();
        validite.setName(EmailCodeAuthenticator.CONFIG_VALIDITE_SECONDES);
        validite.setLabel("Validité du code (secondes)");
        validite.setType(ProviderConfigProperty.STRING_TYPE);
        validite.setDefaultValue(Integer.toString(EmailCodeAuthenticator.VALIDITE_SECONDES_DEFAUT));
        validite.setHelpText("Au-delà, le code est refusé et il faut en redemander un.");

        ProviderConfigProperty essais = new ProviderConfigProperty();
        essais.setName(EmailCodeAuthenticator.CONFIG_ESSAIS_MAX);
        essais.setLabel("Essais avant de brûler le code");
        essais.setType(ProviderConfigProperty.STRING_TYPE);
        essais.setDefaultValue(Integer.toString(EmailCodeAuthenticator.ESSAIS_MAX_DEFAUT));
        essais.setHelpText("Atteint, le code est invalidé : il ne suffit pas de recharger la page.");

        return List.of(validite, essais);
    }

    @Override
    public Authenticator create(KeycloakSession session) {
        // Sans état d'instance : une seule suffit pour tout le serveur.
        return INSTANCE;
    }

    @Override
    public void init(Config.Scope config) {
        // Rien à lire au démarrage : tout est par exécution.
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
        // Aucun autre fournisseur à joindre.
    }

    @Override
    public void close() {
        // Sans ressource à libérer.
    }
}
