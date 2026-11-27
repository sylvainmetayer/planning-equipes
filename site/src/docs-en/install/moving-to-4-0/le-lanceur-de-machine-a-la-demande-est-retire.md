---
ordre: 40
---

### The on-demand machine launcher is removed

- **Before (3.x)**: `SOLVER_WORKER_HOOK_URL` and `SOLVER_WORKER_HOOK_SECRET` connected a launcher that created a rented machine for the duration of a solve, with `SOLVER_WORKER_INSTANCE`, `SOLVER_WORKER_START_TIMEOUT` and `SOLVER_WORKER_IDLE_TIMEOUT`. The Solver screen showed the state of that machine, with "Prepare" and "Stop", and the repository shipped `deploy/launcher/`.
- **After (4.0)**: the application no longer starts or stops any machine. The five variables above have no effect any more: if one is set, startup says so in a warning and carries on; the warning disappears in 5.0. The **permanent worker** (`SOLVER_WORKER_URL`, `SOLVER_WORKER_SECRET`, `SOLVER_WORKER_TLS_FINGERPRINT`, `SOLVER_WORKER_DEFAULT`) does not change, nor does the rule that a remote failure fails the job. The "On this server" or "On the compute machine" choice stays on the *Calculate* tab of the Planning page. On the API side, `POST /api/machine-calcul/preparer` and `POST /api/machine-calcul/arreter` no longer exist, and `GET /api/machine-calcul` returns only `configured` and `remoteByDefault` (`onDemand`, `state`, `stopsAt`, `billedHoursThisMonth` and `lastStopError` are gone).
- **What you do**: if you used the launcher, rent and switch on the machine with your host's tools, start the worker on it ([the procedure](calcul-sur-une-autre-machine.md)) and set `SOLVER_WORKER_URL` and `SOLVER_WORKER_SECRET`. Without that, your solves run on this server. Then remove the five variables from your environment.
