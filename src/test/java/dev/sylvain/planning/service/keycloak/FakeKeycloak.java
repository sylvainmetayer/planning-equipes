package dev.sylvain.planning.service.keycloak;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The slice of Keycloak's admin API that {@link KeycloakUserProvisioning} talks
 * to — users, their realm roles, the invitation mail, the logout — kept in
 * memory behind the JDK's own HTTP server, so the provisioning's decisions run
 * through the real admin client with no Keycloak and no dependency. What a
 * real server enforces (the permissions of the service account) is still
 * proven by the Playwright suite.
 */
final class FakeKeycloak implements AutoCloseable {

    /** One account as the realm holds it. */
    static final class User {
        String id;
        String email;
        String firstName;
        String lastName;
        boolean enabled = true;
        boolean emailVerified;
        final Set<String> roles = new LinkedHashSet<>();
    }

    private static final Pattern USER = Pattern.compile("/admin/realms/planning/users/([^/]+)(/.*)?");

    private static final ObjectMapper JSON = new ObjectMapper();

    final Map<String, User> users = new LinkedHashMap<>();

    /** The realm roles that exist; one absent here is neither assignable nor held. */
    final Set<String> realmRoles = new LinkedHashSet<>(Set.of("animateur", "admin"));

    /** Ids whose invitation mail was asked for. */
    final List<String> invited = new ArrayList<>();

    /** Ids whose sessions were ended. */
    final List<String> loggedOut = new ArrayList<>();

    /** Whole API answers 500 while set: the realm that cannot be reached. */
    boolean down;

    /** Account creation answers 409. */
    boolean refuseCreation;

    /** The role assignment answers 403, like a service account lacking the right. */
    boolean refuseRoles;

    /** The invitation mail answers 500: no SMTP server. */
    boolean refuseMail;

    private int counter;

    private final HttpServer server;

    FakeKeycloak() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", this::handle);
        server.start();
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    synchronized User add(String email, boolean enabled, boolean verified) {
        User user = new User();
        user.id = "u" + ++counter;
        user.email = email;
        user.enabled = enabled;
        user.emailVerified = verified;
        users.put(user.id, user);
        return user;
    }

    synchronized User byEmail(String email) {
        return users.values().stream()
                .filter(user -> email.equalsIgnoreCase(user.email))
                .findFirst()
                .orElse(null);
    }

    private synchronized void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        if (path.endsWith("/protocol/openid-connect/token")) {
            send(
                    exchange,
                    200,
                    "{\"access_token\":\"t\",\"expires_in\":3600,\"refresh_expires_in\":0,\"token_type\":\"Bearer\"}");
            return;
        }
        if (down) {
            send(exchange, 500, "{\"error\":\"down\"}");
            return;
        }
        if (path.equals("/admin/realms/planning/users")) {
            if ("POST".equals(method)) {
                create(exchange, body);
            } else {
                list(exchange);
            }
            return;
        }
        Matcher user = USER.matcher(path);
        if (!user.matches() || !users.containsKey(user.group(1))) {
            send(exchange, 404, "{\"error\":\"not found\"}");
            return;
        }
        User cible = users.get(user.group(1));
        String suite = user.group(2) == null ? "" : user.group(2);
        switch (suite) {
            case "" -> single(exchange, method, cible, body);
            case "/role-mappings/realm/available" ->
                send(
                        exchange,
                        200,
                        roles(realmRoles.stream()
                                .filter(r -> !cible.roles.contains(r))
                                .toList()));
            case "/role-mappings/realm" -> {
                if ("POST".equals(method)) {
                    assign(exchange, cible, body);
                } else {
                    send(exchange, 200, roles(List.copyOf(cible.roles)));
                }
            }
            case "/execute-actions-email" -> {
                if (refuseMail) {
                    send(exchange, 500, "{\"error\":\"smtp\"}");
                } else {
                    invited.add(cible.id);
                    send(exchange, 204, "");
                }
            }
            case "/logout" -> {
                loggedOut.add(cible.id);
                send(exchange, 204, "");
            }
            default -> send(exchange, 404, "{}");
        }
    }

    private void create(HttpExchange exchange, String body) throws IOException {
        if (refuseCreation) {
            send(exchange, 409, "{\"error\":\"exists\"}");
            return;
        }
        JsonNode node = JSON.readTree(body);
        User user = add(
                node.path("email").asText(),
                node.path("enabled").asBoolean(true),
                node.path("emailVerified").asBoolean(false));
        user.firstName = node.path("firstName").asText(null);
        user.lastName = node.path("lastName").asText(null);
        exchange.getResponseHeaders().add("Location", url() + "/admin/realms/planning/users/" + user.id);
        send(exchange, 201, "");
    }

    private void list(HttpExchange exchange) throws IOException {
        Map<String, String> query = query(exchange);
        List<User> trouves = new ArrayList<>();
        String email = query.get("email");
        if (email != null) {
            users.values().stream().filter(u -> email.equalsIgnoreCase(u.email)).forEach(trouves::add);
        } else {
            int first = Integer.parseInt(query.getOrDefault("first", "0"));
            int max = Integer.parseInt(query.getOrDefault("max", "100"));
            trouves.addAll(users.values().stream().skip(first).limit(max).toList());
        }
        ArrayNode array = JSON.createArrayNode();
        trouves.forEach(u -> array.add(represent(u)));
        send(exchange, 200, array.toString());
    }

    private void single(HttpExchange exchange, String method, User cible, String body) throws IOException {
        switch (method) {
            case "PUT" -> {
                JsonNode node = JSON.readTree(body);
                cible.firstName = node.path("firstName").asText(null);
                cible.lastName = node.path("lastName").asText(null);
                cible.enabled = node.path("enabled").asBoolean(true);
                cible.emailVerified = node.path("emailVerified").asBoolean(false);
                send(exchange, 204, "");
            }
            case "DELETE" -> {
                users.remove(cible.id);
                send(exchange, 204, "");
            }
            default -> send(exchange, 200, represent(cible).toString());
        }
    }

    private void assign(HttpExchange exchange, User cible, String body) throws IOException {
        if (refuseRoles) {
            send(exchange, 403, "{\"error\":\"forbidden\"}");
            return;
        }
        for (JsonNode role : JSON.readTree(body)) {
            cible.roles.add(role.path("name").asText());
        }
        send(exchange, 204, "");
    }

    private static ObjectNode represent(User user) {
        ObjectNode node = JSON.createObjectNode();
        node.put("id", user.id);
        node.put("username", user.email);
        node.put("email", user.email);
        node.put("firstName", user.firstName);
        node.put("lastName", user.lastName);
        node.put("enabled", user.enabled);
        node.put("emailVerified", user.emailVerified);
        return node;
    }

    private static String roles(List<String> names) {
        ArrayNode array = JSON.createArrayNode();
        names.forEach(name -> array.addObject().put("id", "r-" + name).put("name", name));
        return array.toString();
    }

    private static Map<String, String> query(HttpExchange exchange) {
        Map<String, String> query = new LinkedHashMap<>();
        String raw = exchange.getRequestURI().getRawQuery();
        if (raw != null) {
            for (String pair : raw.split("&")) {
                String[] kv = pair.split("=", 2);
                query.put(
                        URLDecoder.decode(kv[0], StandardCharsets.UTF_8),
                        kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "");
            }
        }
        return query;
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
