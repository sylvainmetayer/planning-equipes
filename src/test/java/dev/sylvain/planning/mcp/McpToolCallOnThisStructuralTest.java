package dev.sylvain.planning.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkiverse.mcp.server.Tool;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * A write tool never calls another {@code @Tool} of its own bean.
 *
 * <p>ArC intercepts a call on {@code this} like a call from outside, so the
 * callee's {@code JournalOutilInterceptor} runs again and the action is
 * journalled twice (the bug of {@code EchangeJournalMcpTest}). A tool that
 * delegates calls a {@code private} method instead. A read tool is never
 * journalled, so one that still calls another tool is tolerated, argued in
 * {@link #READS_STILL_CALLING_A_TOOL}.</p>
 *
 * <p>A source scan, so it under-detects (a call through a lambda is seen, one
 * through a local alias is not): a floor, not a proof.</p>
 */
class McpToolCallOnThisStructuralTest {

    private static final Path MCP = Path.of("src/main/java/dev/sylvain/planning/mcp");

    private static final String GROUPED_READ =
            "a grouped read dispatching to the read tools it replaced (a read is never"
                    + " journalled, so the second crossing of the interceptor costs a few microseconds, not a line)";

    /** Read tools calling another tool on this, by {@code Class.method}: tolerated, each with its reason. */
    private static final Map<String, String> READS_STILL_CALLING_A_TOOL = Stream.of(
                    "ChangementsJourneeMcpTools.compareDay",
                    "CreneauMcpTools.diagnoseCreneaux",
                    "DiagnosticMcpTools.consultMealsAndBreaks",
                    "DiagnosticMcpTools.consultNeed",
                    "DiagnosticMcpTools.consultRisks",
                    "DisponibiliteMcpTools.consultAvailability",
                    "PlanningMcpTools.analyzeAffectation",
                    "PlanningMcpTools.consultLoad",
                    "PlanningMcpTools.consultPlanning",
                    "PlanningMcpTools.explainAffectation",
                    "PlanningMcpTools.listAffectations",
                    "PlanningMcpTools.simulateAffectation",
                    "PublicationMcpTools.consultPublication",
                    "ScenarioMcpTools.diagnoseScenario",
                    "SolveurMcpTools.consultCalcul")
            .collect(Collectors.toMap(tool -> tool, _ -> GROUPED_READ));

    @Test
    void noWriteToolCallsAnotherToolOnThis() throws Exception {
        List<String> offenders = new ArrayList<>();
        Set<String> readOffenders = new TreeSet<>();
        for (Method tool : OutilsMcp.all()) {
            List<String> siblings = new ArrayList<>();
            for (Method other : tool.getDeclaringClass().getDeclaredMethods()) {
                if (other.isAnnotationPresent(Tool.class) && !other.getName().equals(tool.getName())) {
                    siblings.add(other.getName());
                }
            }
            String body = body(tool);
            for (String sibling : siblings) {
                if (!callsOnThis(body, sibling)) {
                    continue;
                }
                String where = tool.getDeclaringClass().getSimpleName() + "." + tool.getName() + " -> " + sibling;
                if (tool.getAnnotation(Tool.class).annotations().readOnlyHint()) {
                    readOffenders.add(tool.getDeclaringClass().getSimpleName() + "." + tool.getName());
                } else {
                    offenders.add(where);
                }
            }
        }
        assertThat(offenders)
                .as("write tools calling another @Tool on this: the interceptor journals twice, call a private method")
                .isEmpty();
        assertThat(readOffenders)
                .as("read tools calling another @Tool on this: fix them, or argue them in READS_STILL_CALLING_A_TOOL")
                .containsExactlyInAnyOrderElementsOf(READS_STILL_CALLING_A_TOOL.keySet());
    }

    @Test
    void aCallOnThisIsSeenAsACallOrAsAMethodReference() {
        assertThat(callsOnThis("{ return other(x); }", "other")).isTrue();
        assertThat(callsOnThis("{ return this.other(x); }", "other")).isTrue();
        assertThat(callsOnThis("{ list.stream().map(this::other); }", "other")).isTrue();
        assertThat(callsOnThis("{ return service.other(x); }", "other")).isFalse();
        assertThat(callsOnThis("{ return otherThing(x); }", "other")).isFalse();
    }

    private static boolean callsOnThis(String body, String method) {
        String name = Pattern.quote(method);
        return Pattern.compile("(?<![\\w.#:])(?:this\\.)?" + name + "\\s*\\(|\\bthis\\s*::\\s*" + name + "\\b")
                .matcher(body)
                .find();
    }

    /** The braces of the tool's own declaration, read from the source. */
    private static String body(Method tool) throws Exception {
        String source = Files.readString(MCP.resolve(tool.getDeclaringClass().getSimpleName() + ".java"));
        Matcher declaration = Pattern.compile("(?m)^\\s+(?:public |protected )?[\\w<>\\[\\], ?.]+\\s+"
                        + Pattern.quote(tool.getName()) + "\\s*\\(")
                .matcher(source);
        assertThat(declaration.find()).as("declaration of %s", tool.getName()).isTrue();
        int open = source.indexOf('{', declaration.end());
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return source.substring(open, i + 1);
            }
        }
        throw new AssertionError("unbalanced braces after " + tool.getName());
    }
}
