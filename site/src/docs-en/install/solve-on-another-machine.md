---
twin: installer/calcul-sur-une-autre-machine
---
# Solve on another machine

You entrust full solves to a machine faster than your server, a workstation on the network or a machine you rent and leave switched on, without moving the application. The solver uses only **one core**: what sets the duration of a solve is the speed of that core, whatever the size of the machine.

None of what follows is mandatory: **without these variables, everything is solved locally, as always**. The decision is in [ADR 0086](repo:docs/decisions/0086-le-calcul-deporte-un-worker-sans-conteneur-et-un-crochet.md), from which [ADR 0103](repo:docs/decisions/0103-retrait-du-lanceur-de-machine-a-la-demande.md) removes the machine started on demand.

For the jargon-free version, with what the solve does and how long it takes, see [how the schedule solve works](/comment-marche-le-calcul/).

## 1. Choose the option

| Your situation | What you need |
| --- | --- |
| Everything on the same machine | nothing |
| A powerful workstation permanently on the network | a **permanent worker**: `SOLVER_WORKER_URL` |
| The application rented, the solve at your place behind a router | **not served** as is: the application must be able to reach the worker, so you need a tunnel or a VPN on your side |

All connections **start from the application**: the worker does not call it back, so it can stay behind a router with no open port. Only the full solve goes out; partial replanning, the diagnostic and the staffing check stay local.

## 2. Install a permanent worker

It is **the same image**, started with `SOLVER_WORKER_MODE=true`: a different program, which opens no database or e-mail and keeps nothing on disk. It needs the **same version** as the application: it refuses a problem from another one.

On the compute machine, create a certificate, its fingerprint and the worker:

```bash
# A certificate for the worker, and its fingerprint for the application
openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -nodes \
  -keyout key.pem -out crt.pem -days 3650 -subj "/CN=solve-worker"
MDP="$(openssl rand -hex 16)"
openssl pkcs12 -export -inkey key.pem -in crt.pem -out worker.p12 -passout "pass:$MDP"
openssl x509 -in crt.pem -noout -fingerprint -sha256     # → SOLVER_WORKER_TLS_FINGERPRINT

docker run -d --name solve-worker --restart unless-stopped -p 8443:8443 \
  -e SOLVER_WORKER_MODE=true \
  -e SOLVER_WORKER_SECRET="$(openssl rand -hex 32)" \
  -e SOLVER_WORKER_TLS_KEYSTORE=/worker/worker.p12 \
  -e SOLVER_WORKER_TLS_KEYSTORE_PASSWORD="$MDP" \
  -v "$PWD/worker.p12:/worker/worker.p12:ro" \
  ghcr.io/sylvainmetayer/planning-equipes:<version>
```

Note the generated secret (`SOLVER_WORKER_SECRET`): the application must present the same one. The worker takes three quarters of its machine's memory, like the application (`JDK_JAVA_OPTIONS`); 2 GB is enough for an edition of usual size.

| Worker variable | Default | Role |
| --- | --- | --- |
| `SOLVER_WORKER_MODE` | `false` | `true` starts the worker in place of the application |
| `SOLVER_WORKER_SECRET` | — | Mandatory: what the application must present |
| `SOLVER_WORKER_PORT` | `8443` | Listening port |
| `SOLVER_WORKER_TLS_KEYSTORE` | — | PKCS#12 file of the certificate. Without it, the worker speaks HTTP and listens only on the loopback: a development machine, nothing else |
| `SOLVER_WORKER_TLS_KEYSTORE_PASSWORD` | empty | Password of that file |

## 3. Connect the application

In the application's `.env.prod`:

| Variable | Default | Role |
| --- | --- | --- |
| `SOLVER_WORKER_URL` | — | Address of the permanent worker, as `https://`. `http://` is accepted only towards the loopback |
| `SOLVER_WORKER_SECRET` | — | This worker's secret (mandatory with the address) |
| `SOLVER_WORKER_TLS_FINGERPRINT` | — | SHA-256 fingerprint of its certificate: only that one is then accepted, whatever name it carries. Empty: ordinary trust (a certificate signed by a recognised authority) |
| `SOLVER_WORKER_DEFAULT` | `local` | `remote`: a solve goes to the worker when its launch says nothing |

Once configured, the *Calculate* tab of the Planning page offers "On this server" or "On the compute machine" at each launch.

## 4. Know what happens in case of failure

- **A remote solve that fails makes the job fail**, with its reason and the end of the worker's log; nothing falls back to local. Launch again choosing "On this server", or retry. The saved plan is never touched by a failure.
- **The application may restart during a remote solve**: the worker continues, and tracking resumes at the next startup.

## 5. Rent a machine by the hour

The application no longer creates a machine on demand (removed in 4.0). Rent the machine on your side, switch it on when you need it with your host's tools, start the worker on it as in § 2 and enter its address: it remains a permanent worker for the application. If it is switched off, a solve launched "On the compute machine" fails with its reason, and "On this server" remains available.

What leaves your server in this mode, and towards whom, is detailed in [the remote solve](securite-appels-sortants.md#calcul-déporté--ce-qui-sort-et-vers-qui).
