package dev.sylvain.planning.mcp;

/**
 * The free-text fields a person types — a swap request's reason, a comment
 * on a declaration, the admin's answer, the reason of a lock or of an ad hoc
 * constraint — leave over MCP as « was something written », never as what
 * was written.
 *
 * <p>The privacy rule of issue #107 keeps names and birth dates inside, and a
 * free text is where both come back in: « je remplace Marie D. », « rendez-vous
 * médical ». No filter can tell those apart from a harmless sentence, and an
 * assistant acts on ids, not on the wording. Knowing that a reason exists is
 * what it needs to point the operator at the screen that shows it.</p>
 *
 * <p>Writing stays open: the tools that refuse a request or a declaration
 * still take the comment the animateur will read. Only reading is closed.</p>
 */
final class TextesLibres {

    private TextesLibres() {}

    static boolean renseigne(String texte) {
        return texte != null && !texte.isBlank();
    }
}
