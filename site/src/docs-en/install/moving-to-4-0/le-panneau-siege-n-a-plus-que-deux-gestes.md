---
ordre: 233
---

### The Seat panel has only two "what if" gestures

- **Before (3.x)**: the Seat panel of the Planning page offered neighbouring gestures, each on its own: "Replace" (the viable replacements, applied in one click), "Move to..." (written with no preview), "Who can hold this seat?" and "Why them?", read each time a held seat was opened. On the API side, `POST /api/postes/{posteId}/deplacement/simulation` simulated a move, and `POST /api/postes/{posteId}/simulation-swap` simulated a hand-over on a plan sent by the caller. A read-only user could call neither.
- **After (4.0)**: two gestures. "Simulate a change" brings together the repair suggestions, the move to another seat and the bench; it shows the before and the after of the change chosen, and "Apply" writes it with the same checks as before (solve under way, lock, frozen past, person displayed). "Explain" says why this person is on this seat. Free, Mark absent and Lock stay beside them. On the API side, the simulation and the suggestions are read with `GET /api/postes/{posteId}/simulation?cible=|animateur=` and `GET /api/postes/{posteId}/suggestions`: these are reads, open to a read-only user, who still cannot apply.
- **What you do**: nothing in the application. A script that called `POST .../deplacement/simulation` or `POST .../simulation-swap` keeps working until 5.0; move it to `GET .../simulation`, which reads the saved plan with the edition's settings, before 5.0, which will remove the two old routes.
