-- Development-only override of the server's notion of "today", for the mode
-- jour J screen (issue #297).
--
-- Persisted rather than kept in memory for the same reason the manual solve
-- budget is (V20): a setting that lives in one process is a setting that
-- silently resets under a hot reload, and the developer then wonders why the
-- screen went back to showing nothing.
--
-- Not partitioned by edition on purpose: this is the clock of the server, not
-- a property of an event. NULL means "use the real date", which is the only
-- state a deployed instance can ever be in — the write path refuses outside
-- dev mode, so this row stays NULL there.
CREATE TABLE IF NOT EXISTS horloge_jour_j (
    id INTEGER PRIMARY KEY,
    date_du_jour DATE,
    CONSTRAINT horloge_jour_j_singleton CHECK (id = 1)
);

INSERT INTO horloge_jour_j (id, date_du_jour)
VALUES (1, NULL)
ON CONFLICT (id) DO NOTHING;
