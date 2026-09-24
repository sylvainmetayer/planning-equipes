package dev.sylvain.planning.service.analyse;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.FenetreHoraire;
import dev.sylvain.planning.domain.HoraireStand;
import dev.sylvain.planning.domain.ModeHoraire;
import dev.sylvain.planning.domain.OuvertureStand;
import dev.sylvain.planning.domain.TypeJoursHoraire;
import dev.sylvain.planning.service.analyse.HoraireRuleOverlaps.EventDay;
import dev.sylvain.planning.service.analyse.HoraireRuleOverlaps.Findings;
import java.io.IOException;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * The case file the rule editor's mirror reads too
 * ({@code core/horaire-stand.spec.ts}): the same rules must yield the same
 * findings on both sides, or the editor warns about something the Ouvertures
 * screen does not show — or stays silent about something it does.
 */
class HoraireRuleOverlapsSharedCasesTest {

    static final Path CAS = Path.of("src/main/webui/src/app/core/horaire-stand-anomalies.cas.json");

    @TestFactory
    Stream<DynamicTest> theSharedCasesYieldTheExpectedFindings() throws IOException {
        JsonNode racine = new ObjectMapper().readTree(CAS.toFile());
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode cas : racine.get("cas")) {
            tests.add(DynamicTest.dynamicTest(cas.get("nom").asText(), () -> {
                Findings findings = HoraireRuleOverlaps.detect(
                        horaires(cas.get("horaires")),
                        exceptionDates(cas),
                        openings(cas.get("ouvertures")),
                        days(cas),
                        cas.get("effectifParDefaut").isNull()
                                ? null
                                : cas.get("effectifParDefaut").asInt());
                assertThat(asJson(findings)).isEqualTo(expected(cas.get("attendu")));
            }));
        }
        assertThat(tests).hasSizeGreaterThan(10);
        return tests.stream();
    }

    private static List<HoraireStand> horaires(JsonNode noeuds) {
        List<HoraireStand> horaires = new ArrayList<>();
        for (JsonNode noeud : noeuds) {
            List<FenetreHoraire> fenetres = new ArrayList<>();
            for (JsonNode fenetre : noeud.get("fenetres")) {
                fenetres.add(new FenetreHoraire(
                        time(fenetre.get("heureDebut")),
                        time(fenetre.get("heureFin")),
                        integer(fenetre.get("effectif"))));
            }
            HoraireStand horaire = new HoraireStand(
                    null,
                    ModeHoraire.valueOf(noeud.get("mode").asText()),
                    TypeJoursHoraire.valueOf(noeud.get("jours").asText()),
                    fenetres);
            TreeSet<DayOfWeek> jours = new TreeSet<>();
            noeud.get("joursSemaine").forEach(jour -> jours.add(DayOfWeek.valueOf(jour.asText())));
            horaire.setJoursSemaine(jours);
            horaire.setDateDebut(date(noeud.get("dateDebut")));
            horaire.setDateFin(date(noeud.get("dateFin")));
            TreeSet<LocalDate> dates = new TreeSet<>();
            noeud.get("dates").forEach(date -> dates.add(LocalDate.parse(date.asText())));
            horaire.setDates(dates);
            horaires.add(horaire);
        }
        return horaires;
    }

    private static List<OuvertureStand> openings(JsonNode noeuds) {
        List<OuvertureStand> ouvertures = new ArrayList<>();
        for (JsonNode noeud : noeuds) {
            ouvertures.add(new OuvertureStand(
                    null,
                    date(noeud.get("date")),
                    time(noeud.get("heureDebut")),
                    time(noeud.get("heureFin")),
                    null,
                    integer(noeud.get("effectif"))));
        }
        return ouvertures;
    }

    private static List<LocalDate> exceptionDates(JsonNode cas) {
        List<LocalDate> dates = new ArrayList<>();
        cas.get("fermetures").forEach(date -> dates.add(LocalDate.parse(date.asText())));
        cas.get("ouvertures").forEach(ouverture -> dates.add(date(ouverture.get("date"))));
        return dates;
    }

    /** The case's days, or — when it gives its timeslots instead — the days production derives from them. */
    private static List<EventDay> days(JsonNode cas) {
        if (cas.has("creneaux")) {
            List<Creneau> creneaux = new ArrayList<>();
            for (JsonNode noeud : cas.get("creneaux")) {
                creneaux.add(new Creneau(
                        null, 0, date(noeud.get("date")), time(noeud.get("heureDebut")), time(noeud.get("heureFin"))));
            }
            return OuvertureStandsAnalyzer.eventDays(creneaux);
        }
        List<EventDay> jours = new ArrayList<>();
        for (JsonNode noeud : cas.get("jours")) {
            LocalTime fin = time(noeud.get("fin"));
            jours.add(new EventDay(date(noeud.get("date")), fin.toSecondOfDay() / 60));
        }
        return jours;
    }

    /** The findings in the case file's own vocabulary, so one comparison says what differs. */
    private static Map<String, Object> asJson(Findings findings) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put(
                "reglesChevauchantes",
                findings.rulesOverlaps().stream()
                        .map(overlap -> map(
                                "regle", overlap.rule(),
                                "autreRegle", overlap.otherRule(),
                                "date", overlap.date().toString(),
                                "debut", HoraireRuleOverlaps.hour(overlap.start()),
                                "fin", HoraireRuleOverlaps.hour(overlap.end()),
                                "effectif", overlap.effectif(),
                                "autreEffectif", overlap.otherEffectif()))
                        .toList());
        json.put(
                "reglesMasquees",
                findings.maskedRules().stream()
                        .map(masked -> map(
                                "regle", masked.rule(),
                                "masquantes", masked.maskingRules(),
                                "exceptions", masked.maskedByExceptions()))
                        .toList());
        json.put(
                "fenetresChevauchantes",
                findings.windowsOverlaps().stream()
                        .map(overlap -> map(
                                "regle", overlap.rule(),
                                "date",
                                        overlap.date() == null
                                                ? null
                                                : overlap.date().toString(),
                                "fenetre", overlap.window(),
                                "autreFenetre", overlap.otherWindow(),
                                "debut", HoraireRuleOverlaps.hour(overlap.start()),
                                "fin", HoraireRuleOverlaps.hour(overlap.end()),
                                "effectif", overlap.effectif(),
                                "autreEffectif", overlap.otherEffectif()))
                        .toList());
        return json;
    }

    private static Map<String, Object> expected(JsonNode attendu) {
        Map<String, Object> json = new LinkedHashMap<>();
        for (String famille : List.of("reglesChevauchantes", "reglesMasquees", "fenetresChevauchantes")) {
            List<Object> lignes = new ArrayList<>();
            for (JsonNode ligne : attendu.get(famille)) {
                Map<String, Object> champs = new LinkedHashMap<>();
                ligne.properties().forEach(entree -> champs.put(entree.getKey(), value(entree.getValue())));
                lignes.add(champs);
            }
            json.put(famille, lignes);
        }
        return json;
    }

    private static Object value(JsonNode noeud) {
        if (noeud.isNull()) {
            return null;
        }
        if (noeud.isInt()) {
            return noeud.asInt();
        }
        if (noeud.isBoolean()) {
            return noeud.asBoolean();
        }
        if (noeud.isArray()) {
            List<Object> valeurs = new ArrayList<>();
            noeud.forEach(element -> valeurs.add(value(element)));
            return valeurs;
        }
        return noeud.asText();
    }

    private static Map<String, Object> map(Object... paires) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < paires.length; i += 2) {
            map.put((String) paires[i], paires[i + 1]);
        }
        return map;
    }

    private static LocalTime time(JsonNode noeud) {
        return noeud == null || noeud.isNull() ? null : LocalTime.parse(noeud.asText());
    }

    private static LocalDate date(JsonNode noeud) {
        return noeud == null || noeud.isNull() ? null : LocalDate.parse(noeud.asText());
    }

    private static Integer integer(JsonNode noeud) {
        return noeud == null || noeud.isNull() ? null : noeud.asInt();
    }
}
