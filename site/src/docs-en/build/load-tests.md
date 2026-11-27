---
twin: comprendre/tests-de-charge
---
# Load testing a public demo

Before you open a [demo instance](../installer/instance-de-demo.md) to everyone, you check that it holds the visitors who will arrive together. Four [k6](https://grafana.com/docs/k6/) scenarios measure it. They live in the repository, under [`src/test/charge/`](repo:src/test/charge/README.md), and run through the official k6 Docker image: there is nothing to install besides Docker, and no npm or Maven dependency.

## The four scenarios

| Scenario | What it plays | What fails it |
| --- | --- | --- |
| `visiteur` | N visitors at once: login through the form, then the reads that Home, Planning, Diagnostic and Rules make when they open | p95, error rate, a single 5xx |
| `calculs` | N visitors press "Solve" at the same instant | more than one solve running and `FILE_MAX` queued, a refusal without a sentence or other than a 409 or a 429, a 5xx |
| `espace` | the demo's espace animateur: code shown on screen, session, planning, the espace's ICS and the subscription feed | p95, error rate, a 5xx, fewer than 90 % of sessions opened |
| `pic` | ramp from 0 to N visitors in one minute, then a plateau | p95, error rate, a 5xx |

Each scenario carries its thresholds: a crossed threshold makes `lancer.sh` exit in error. The p95 (1.5 s), the error rate (1 %), N and the durations are set through variables. The folder's README lists them.

## Running them

The password of the `admin` account is passed as a variable, never written in the repository nor on the k6 command line:

```bash
# A local demo, on a dump from scripts/demo/generer-dump.sh
ADMIN_PASSWORD=… src/test/charge/demo-locale.sh reference.dump

# One scenario against it, or against the public demo
BASE_URL=http://127.0.0.1:18080 ADMIN_PASSWORD=… N=20 src/test/charge/lancer.sh pic

src/test/charge/demo-locale.sh --arreter
```

Run k6 from another machine than the one serving the demo: on the same one, it takes from the server the processor it measures. Raise N step by step (5, 10, 20, 40…) and watch at each step the p95 and what the application container consumes (`docker stats`). The limit is the step where a threshold breaks.

On a demo with a single edition, the queue never fills: an edition has only one solve waiting behind the running one, and the next ones are refused with a 409 and a sentence telling the visitor to wait. The threshold checks that `FILE_MAX` plus one is not exceeded, not that it is reached.

The memory limits of the local stack are those of `docker-compose.prod.yml` (2 GB for the application, `APP_MEM_LIMIT` to try another one): measured without them, the JVM takes three quarters of the machine and the measurement does not hold for the demo.

The `calculs` scenario uses up the instance's hourly quota (`SOLVER_MAX_SOLVES_PER_HOUR`), and the solves it queues hold the processor for several minutes. Do not play it on an open demo.

## The on-demand workflow

The `.github/workflows/charge.yml` workflow only starts by hand, from the Actions tab, never on a PR. It makes a dump with the chosen image (`:main` by default, or a version), starts the demo in the runner and plays the four scenarios with small N values. It tells you a version does not break under a few visitors; a shared runner does not tell you where a real server's limit is. To find it, play `lancer.sh` against a machine the size of your demo host.
