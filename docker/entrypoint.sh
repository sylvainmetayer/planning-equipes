#!/bin/sh
# One image, two programs (ADR 0086). SOLVER_WORKER_MODE=true starts the solve
# worker — a plain Java program on the application's own classes, without its
# container: no database, no migration, no scheduled job, no mail. Anything
# else starts the application, as the image always has.
set -eu
if [ "${SOLVER_WORKER_MODE:-false}" = "true" ]; then
    exec java -cp '/app/app/*:/app/lib/main/*:/app/lib/boot/*' \
        dev.sylvain.planning.service.solve.SolveWorkerServer "$@"
fi
exec java -jar /app/quarkus-run.jar "$@"
