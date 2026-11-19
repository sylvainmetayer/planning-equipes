package dev.sylvain.planning.solver.handsearch;

/**
 * The engine refuses a problem, or a plan it can no longer order: said, where
 * the alternative was a score computed wrong. The message completes « Le
 * moteur direct ne peut pas résoudre ce problème : » on the job.
 */
public final class HandSearchException extends RuntimeException {

    HandSearchException(String message) {
        super(message);
    }
}
