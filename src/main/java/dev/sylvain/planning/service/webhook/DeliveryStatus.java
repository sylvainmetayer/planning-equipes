package dev.sylvain.planning.service.webhook;

/** Where one delivery stands, as the journal of a webhook shows it. */
public enum DeliveryStatus {

    /** Not delivered yet: the first attempt is under way, or a retry is scheduled. */
    PENDING,

    /** The receiver answered 2xx. */
    DELIVERED,

    /** Every attempt of the schedule failed on something worth retrying. */
    FAILED,

    /**
     * Given up at once: a 4xx other than 408 and 429, a redirect (never
     * followed), an address the guard refused, or a webhook since disabled.
     */
    ABANDONED
}
