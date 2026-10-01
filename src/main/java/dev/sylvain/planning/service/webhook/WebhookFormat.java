package dev.sylvain.planning.service.webhook;

/**
 * How a delivery is shaped for its receiver, and where its secret lives.
 *
 * <p>The split that matters is the last column: for {@link #GENERIC} the
 * address is an ordinary URL and the secret signs the body; for the three chat
 * presets the address <b>is</b> the secret — anybody holding a Slack incoming
 * webhook URL can post to that channel — so it is stored encrypted and never
 * shown again; Telegram's address is fixed and its secret is the bot token.</p>
 */
public enum WebhookFormat {

    /** Signed JSON, for an automation tool (n8n, Node-RED, Make…). The secret is the HMAC key. */
    GENERIC,

    /** Slack incoming webhook: {@code {"text": …}} in mrkdwn. The URL is the secret. */
    SLACK,

    /** Discord channel webhook: {@code content} plus one embed. The URL is the secret. */
    DISCORD,

    /**
     * A Matrix bridge exposing a generic incoming webhook (hookshot):
     * {@code {"text", "html"}}. Matrix itself has no incoming webhook, and its
     * client-server API would need an account token. The URL is the secret.
     */
    MATRIX,

    /**
     * Telegram Bot API {@code sendMessage}: the address is fixed, the chat is
     * named by {@code chatId}, and the secret is the bot token.
     */
    TELEGRAM;

    /** Whether the address an admin types is itself the secret, stored encrypted and masked on read. */
    public boolean addressIsSecret() {
        return this == SLACK || this == DISCORD || this == MATRIX;
    }
}
