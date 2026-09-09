package dev.sylvain.planning.scenario;

/**
 * A scenario document the application cannot make sense of, with a message
 * written for the person who wrote the file rather than for the parser that
 * choked on it.
 */
public class ScenarioFormatException extends RuntimeException {

    public ScenarioFormatException(String message) {
        super(message);
    }

    public ScenarioFormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
