package dev.sylvain.planning.service;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A scenario, once read, written out as text that can be compared line by line.
 *
 * <p>Walked by reflection rather than field by field, and deliberately: the
 * point of this form is to notice a change nobody meant to make, and a
 * hand-written walker only ever covers the fields its author remembered. A
 * field added to the domain tomorrow appears here on its own.</p>
 *
 * <p>Two rules decide what counts as a difference:</p>
 * <ul>
 *   <li><b>Lists keep their order.</b> The order a scenario declares its
 *       créneaux and its postes in is part of what it says, not an accident of
 *       iteration — sorting them here would hide a reordering regression.</li>
 *   <li><b>Sets and maps are sorted.</b> Theirs is an accident of iteration:
 *       {@code joursIndisponibles} is a {@code HashSet}, {@code competences} a
 *       {@code HashMap}, and comparing them unsorted would fail on nothing.</li>
 * </ul>
 *
 * <p>Volatile fields are left out by name, because they carry a clock or a
 * random draw and would make every run differ from the last.</p>
 */
public final class FormeCanonique {

    /** Fields whose value is a timestamp or a random draw, never a scenario's content. */
    private static final Set<String> VOLATILES = Set.of("modifieLe", "accessToken");

    private static final int PROFONDEUR_MAX = 12;

    private FormeCanonique() {}

    /**
     * The same form, folded to one line per element.
     *
     * <p>The full text runs to 1.4 Mio on a real edition — too much to keep in
     * the repository, and unreadable in a diff. Every line is therefore
     * gathered under the element it belongs to (its path up to the first
     * index, so {@code .planning.postes[912].stand} counts under
     * {@code .planning.postes[912]}) and replaced by a digest of that
     * element's lines. Lines that belong to no element — the sections, the
     * parameters — are kept <b>verbatim</b>, because those are the ones worth
     * reading in a diff and there are few of them.</p>
     *
     * <p>A difference therefore names the element that moved, and the test
     * writes both full forms next to it for whoever needs to see the field.</p>
     */
    public static List<String> empreintes(Object racine) {
        Map<String, StringBuilder> parElement = new LinkedHashMap<>();
        for (String ligne : of(racine).split("\n")) {
            if (ligne.isBlank()) {
                continue;
            }
            // A line belonging to no element is its own key: gathering them all
            // under one common key would emit them at the position of the first,
            // and the order of the walk would stop meaning anything.
            String element = element(ligne);
            parElement
                    .computeIfAbsent(element.isEmpty() ? ligne : element, cle -> new StringBuilder())
                    .append(ligne)
                    .append('\n');
        }
        List<String> empreintes = new ArrayList<>();
        parElement.forEach(
                (cle, lignes) -> empreintes.add(cle.contains("[") ? cle + "  " + digest(lignes.toString()) : cle));
        return empreintes;
    }

    /** @return the path up to and including the first index, empty when the line carries none */
    private static String element(String ligne) {
        int ouvrante = ligne.indexOf('[');
        if (ouvrante < 0) {
            return "";
        }
        int fermante = ligne.indexOf(']', ouvrante);
        return fermante < 0 ? "" : ligne.substring(0, fermante + 1);
    }

    private static String digest(String contenu) {
        try {
            byte[] empreinte = MessageDigest.getInstance("SHA-256").digest(contenu.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexa = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                hexa.append(String.format("%02x", empreinte[i]));
            }
            return hexa.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 introuvable", e);
        }
    }

    public static String of(Object racine) {
        StringBuilder texte = new StringBuilder();
        write(texte, "", racine, new IdentityHashMap<>(), 0);
        return texte.toString();
    }

    private static void write(
            StringBuilder texte, String chemin, Object valeur, IdentityHashMap<Object, String> vus, int profondeur) {
        if (valeur == null) {
            texte.append(chemin).append(" = null\n");
            return;
        }
        if (isLeaf(valeur)) {
            texte.append(chemin).append(" = ").append(valeur).append('\n');
            return;
        }
        if (profondeur > PROFONDEUR_MAX) {
            texte.append(chemin).append(" = …profondeur max\n");
            return;
        }
        if (valeur instanceof Optional<?> optionnel) {
            // Unwrapped before the shared-object check: Optional.empty() is a
            // singleton, so two unrelated empty sections would look exactly like
            // two references to the same object.
            write(texte, chemin, optionnel.orElse(null), vus, profondeur + 1);
            return;
        }
        if (valeur instanceof Collection<?> collection && collection.isEmpty()
                || valeur instanceof Map<?, ?> map && map.isEmpty()) {
            texte.append(chemin).append(" = (vide)\n");
            return;
        }
        String dejaVu = vus.get(valeur);
        if (dejaVu != null) {
            // A poste points at its stand, which the scenario also lists: writing
            // it out twice would say nothing and cost thousands of lines.
            texte.append(chemin).append(" -> ").append(dejaVu).append('\n');
            return;
        }
        vus.put(valeur, chemin);

        if (valeur instanceof Map<?, ?> map) {
            // On the entries, not on the keys: two distinct keys whose textual
            // form coincides — an Integer 1 and a String "1", an enum and its
            // name — would both render the first one's value, and the
            // difference would vanish.
            map.entrySet().stream()
                    .sorted(Comparator.comparing(entree -> String.valueOf(entree.getKey())))
                    .forEach(entree ->
                            write(texte, chemin + "{" + entree.getKey() + "}", entree.getValue(), vus, profondeur + 1));
            return;
        }
        if (valeur instanceof Collection<?> collection) {
            // The objects sorted on their form, not their forms sorted:
            // rendering an element as a block of text would emit it with no
            // path, and two elements producing the same line would merge.
            List<?> elements = collection instanceof Set<?> ensemble
                    ? ensemble.stream()
                            .sorted(Comparator.comparing(FormeCanonique::of))
                            .toList()
                    : new ArrayList<>(collection);
            int index = 0;
            for (Object element : elements) {
                write(texte, chemin + "[" + index++ + "]", element, vus, profondeur + 1);
            }
            return;
        }
        writeFields(texte, chemin, valeur, vus, profondeur);
    }

    private static void writeFields(
            StringBuilder texte, String chemin, Object valeur, IdentityHashMap<Object, String> vus, int profondeur) {
        List<Field> champs = new ArrayList<>();
        for (Class<?> type = valeur.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field champ : type.getDeclaredFields()) {
                if (!Modifier.isStatic(champ.getModifiers())
                        && !champ.isSynthetic()
                        && !VOLATILES.contains(champ.getName())) {
                    champs.add(champ);
                }
            }
        }
        champs.sort((a, b) -> a.getName().compareTo(b.getName()));
        for (Field champ : champs) {
            try {
                champ.setAccessible(true);
                write(texte, chemin + "." + champ.getName(), champ.get(valeur), vus, profondeur + 1);
            } catch (ReflectiveOperationException | RuntimeException e) {
                texte.append(chemin)
                        .append('.')
                        .append(champ.getName())
                        .append(" = …illisible (")
                        .append(e.getClass().getSimpleName())
                        .append(")\n");
            }
        }
    }

    private static boolean isLeaf(Object valeur) {
        return valeur instanceof CharSequence
                || valeur instanceof Number
                || valeur instanceof Boolean
                || valeur instanceof Character
                || valeur instanceof Enum<?>
                || valeur.getClass().getName().startsWith("java.time.");
    }
}
