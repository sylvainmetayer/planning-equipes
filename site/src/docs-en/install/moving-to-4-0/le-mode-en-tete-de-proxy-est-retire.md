---
ordre: 30
---

### Proxy header mode is removed

- **Before (3.x)**: `REMOTE_USER_ENABLED=true`, with `REMOTE_USER_SECRET`, let an access proxy authenticate the administrator (`REMOTE_USER_ADMIN_EMAIL`) and open the staff espace without a code, through a header. A 3.x release already warned that it was "deprecated".
- **After (4.0)**: the mechanism no longer exists. The five variables `REMOTE_USER_ENABLED`, `REMOTE_USER_HEADER`, `REMOTE_USER_SECRET_HEADER`, `REMOTE_USER_SECRET` and `REMOTE_USER_ADMIN_EMAIL` have no effect any more. If one is set, startup says so in a warning and carries on; the warning disappears in 5.0. A proxy's header no longer opens anything, neither the administration nor a staff member's espace.
- **What you do**: **before** upgrading, set up [sign-in through an OIDC provider](oidc.md) or create [local accounts](comptes-locaux.md), and check that a named administrator account opens. Staff members go back to the six-digit code sent by e-mail. Then remove the `REMOTE_USER_*` variables from your environment. Your proxy can stay in front of the instance as a network filter, but it no longer replaces sign-in.
