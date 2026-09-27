package dev.sylvain.planning;

import io.quarkus.test.junit.callback.QuarkusTestBeforeEachCallback;
import io.quarkus.test.junit.callback.QuarkusTestMethodContext;
import io.restassured.RestAssured;
import io.restassured.filter.Filter;
import io.restassured.filter.FilterContext;
import io.restassured.response.Response;
import io.restassured.specification.FilterableRequestSpecification;
import io.restassured.specification.FilterableResponseSpecification;

/**
 * Asks the server to close every RestAssured connection once it has answered.
 *
 * <p>RestAssured builds a new Apache HttpClient for each request and never
 * shuts it down: the keep-alive connection stays open on the server until the
 * garbage collector reclaims the abandoned client, and the server's
 * {@code quarkus.http.limits.max-connections} counts it all that time. On a
 * runner whose heap collects rarely, a few hundred requests between two
 * collections reach the cap, and the server then closes every new connection
 * unanswered — {@code NoHttpResponse} on a run of classes, until a collection
 * frees the backlog. RestAssured's own {@code closeIdleConnectionsAfterEachResponse}
 * is no cure: it closes the socket before a lazily read body is consumed.
 * Registered through {@code META-INF/services}, so no test can forget it.</p>
 */
public class RestAssuredConnectionRelease implements QuarkusTestBeforeEachCallback {

    @Override
    public void beforeEach(QuarkusTestMethodContext context) {
        if (RestAssured.filters().stream().noneMatch(ConnectionClose.class::isInstance)) {
            RestAssured.filters(new ConnectionClose());
        }
    }

    /** Adds {@code Connection: close} unless the test chose its own connection header. */
    static final class ConnectionClose implements Filter {
        @Override
        public Response filter(
                FilterableRequestSpecification request, FilterableResponseSpecification response, FilterContext ctx) {
            if (!request.getHeaders().hasHeaderWithName("Connection")) {
                request.header("Connection", "close");
            }
            return ctx.next(request, response);
        }
    }
}
