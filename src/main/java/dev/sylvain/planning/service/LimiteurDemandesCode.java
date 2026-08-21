package dev.sylvain.planning.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Débit des demandes de code d'accès de l'espace animateur.
 *
 * <p>Sans lui, {@code POST /api/espace-animateur/{jeton}/code} est un envoi de
 * mail déclenchable en boucle par quiconque tient le lien : on noie la boîte
 * de l'animateur sous les codes, on épuise le quota du serveur SMTP, et on
 * remet à cinq le compteur d'essais du code à chaque demande.</p>
 *
 * <p>Ce qui est compté, ce sont les codes <b>jamais utilisés</b> : ouvrir la
 * session avec le code reçu efface le compteur. Un animateur qui se connecte
 * normalement, même souvent, n'atteint donc jamais le plafond — seule
 * l'accumulation de demandes sans suite, qui est exactement l'abus, y mène.
 * La fenêtre est glissante depuis la première demande non suivie d'effet.</p>
 *
 * <p>En mémoire, comme le verrouillage de {@code McpResource} : l'application
 * est mono-instance, et un redémarrage n'est pas à la portée de l'attaquant
 * que ce compteur vise. La limitation par adresse IP, elle, appartient au
 * reverse proxy — voir {@code docs/securite.md}.</p>
 */
@ApplicationScoped
public class LimiteurDemandesCode {

    /** Ce que la demande apprend à l'appelant : passe, ou repasse dans tant de secondes. */
    public record Verdict(boolean autorise, long secondesAvantNouvelEssai) {

        static Verdict ok() {
            return new Verdict(true, 0);
        }
    }

    @ConfigProperty(name = "planning.espace.code.max-demandes")
    int maxDemandes;

    @ConfigProperty(name = "planning.espace.code.fenetre")
    Duration fenetre;

    private final Map<String, Fenetre> parAnimateur = new ConcurrentHashMap<>();

    /** Demandes non consommées et début de la fenêtre qui les compte. */
    private record Fenetre(int demandes, Instant debut) {
    }

    /**
     * Consomme un jeton de débit pour {@code cle}. Le verdict négatif porte le
     * délai restant avant que la fenêtre ne se rouvre, tel quel pour
     * {@code Retry-After}.
     */
    public Verdict demander(String cle) {
        Instant maintenant = Instant.now();
        Fenetre apres = parAnimateur.compute(cle, (ignore, courante) -> {
            if (courante == null || courante.debut().plus(fenetre).isBefore(maintenant)) {
                return new Fenetre(1, maintenant);
            }
            return new Fenetre(courante.demandes() + 1, courante.debut());
        });
        if (apres.demandes() <= maxDemandes) {
            return Verdict.ok();
        }
        long restant = Duration.between(maintenant, apres.debut().plus(fenetre)).toSeconds();
        return new Verdict(false, Math.max(restant, 1));
    }

    /** Le code a servi : la série de demandes sans suite s'arrête là. */
    public void oublier(String cle) {
        parAnimateur.remove(cle);
    }
}
