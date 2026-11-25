package dev.sylvain.planning;

import java.util.UUID;

/** Passwords for the tests, generated per run so no literal secret sits in the repository. */
public final class TestPasswords {

    /** A valid password, long enough for the policy. */
    public static final String LONG = "pw-" + UUID.randomUUID();

    /** A password that matches no account. */
    public static final String WRONG = "wrong-" + UUID.randomUUID();

    private TestPasswords() {}
}
