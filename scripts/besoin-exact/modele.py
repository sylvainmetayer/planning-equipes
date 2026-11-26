"""Minimum de personnes d'un rang de la gamme, par une RELAXATION exacte (CP-SAT) des règles dures.

Outillage d'analyse, hors application et hors image (le Dockerfile ne copie que pom.xml, src et .git) :
il prouve, rang par rang, le besoin que la borne de StaffingAnalyzer minore (ADR 0098).
Dépendance : `pip install ortools`, sur une machine d'analyse seulement. Les sièges viennent d'un export
de la gamme (JSON {params, seats}) ; un plan trouvé (--out) se note ensuite avec les vraies règles.

  modele.py rungNN.json --max N [--mode optimize|decide --n N] [--seconds S] [--workers W] [--rules LISTE]

Règles (--rules, séparées par des virgules), chacune exacte ou plus faible que la vraie :
  overlap   une personne ne tient jamais deux sièges qui se chevauchent (et chaque siège a une personne)
  daily     travail effectif d'un jour <= 10 h ; avec `continu`, la pause d'un étirement > 6 h est déduite
  weekly    travail effectif d'une semaine <= plafond hebdomadaire (même déduction)
  days      au plus 6 jours travaillés par semaine ISO
  rest      repos quotidien : deux sièges de jours voisins ne se tiennent pas si l'écart < repos dû
  meal      coupure repas, exacte sur une grille de 30 minutes
  continu   travail continu : la 7e heure d'un étirement ne se tient pas sans relais d'un siège du même stand
  hebdo     repos hebdomadaire, version PLUS STRICTE que la règle (un plan trouvé est réel ; un
            « infaisable » avec elle ne prouve rien) : à ne pas mêler aux preuves d'infaisabilité
Un « INFEASIBLE » sans `hebdo` prouve que N personnes ne suffisent PAS sous les vraies règles.
Les sièges de même (jour, début, fin, cellules non relayables) sont interchangeables : une variable
booléenne par (créneau, personne), la somme vaut le nombre de sièges.
"""
import argparse, collections, datetime, json, sys, time
from ortools.sat.python import cp_model

ap = argparse.ArgumentParser()
ap.add_argument("src")
ap.add_argument("--max", type=int, required=True, help="effectif maximal essayé (le besoin trouvé par la grille)")
ap.add_argument("--min", type=int, default=0, help="borne basse connue : N-1 est testé d'abord si 0")
ap.add_argument("--seconds", type=float, default=1800)
ap.add_argument("--workers", type=int, default=8)
ap.add_argument("--mode", choices=["optimize", "decide"], default="optimize")
ap.add_argument("--n", type=int, help="mode decide : tester ce seul effectif")
ap.add_argument("--out", help="écrit {siège: personne} quand une solution est trouvée")
ap.add_argument("--rules", default="overlap,daily,weekly,days,rest")
args = ap.parse_args()
RULES = set(args.rules.split(","))

d = json.load(open(args.src))
P = d["params"]
seats = d["seats"]
for s in seats:
    if s["end"] <= s["start"]:
        s["end"] += 1440
dates = sorted({s["date"] for s in seats})
D = {x: i for i, x in enumerate(dates)}
pause = P["pause"]
CELL = 30
# Relay (ADR 0048): the break owed inside a stretch is real when another seat of the same stand covers it
# (every seat is filled, so that seat is held by somebody else). nr = the cells of a seat nobody can relay.
by_stand_day = collections.defaultdict(list)
for s in seats:
    by_stand_day[s["stand"], s["date"]].append(s)
for s in seats:
    s["nr"] = tuple(c for c in range(s["start"], s["end"], CELL)
                    if not any(o is not s and o["start"] <= c and o["end"] >= c + pause for o in by_stand_day[s["stand"], s["date"]])) if "continu" in RULES else ()
groups = collections.OrderedDict()
for s in seats:
    groups.setdefault((D[s["date"]], s["start"], s["end"], s["nr"]), []).append(s)
slots = [((k[0], k[1], k[2]), len(v), k[3]) for k, v in groups.items()]
members = list(groups.values())
dayCap = 600 + pause
weekCap = P["weekMax"] + 6 * pause
rest = P["dailyRest"]
print(f"{len(seats)} sièges, {len(slots)} créneaux distincts, {len(dates)} jours, pause={pause} repos={rest}", flush=True)


def build(N, minimize):
    m = cp_model.CpModel()
    x = {}
    for g, ((dd, st, en), n, nr) in enumerate(slots):
        for a in range(N):
            x[g, a] = m.new_bool_var("")
        m.add(sum(x[g, a] for a in range(N)) == n)
    byday = collections.defaultdict(list)
    for g, ((dd, st, en), n, nr) in enumerate(slots):
        byday[dd].append(g)
    if "overlap" in RULES:
        for a in range(N):
            ivs = []
            for g, ((dd, st, en), n, nr) in enumerate(slots):
                ivs.append(m.new_optional_fixed_size_interval_var(dd * 1440 + st, en - st, x[g, a], ""))
            m.add_no_overlap(ivs)
    worked = {}
    for a in range(N):
        for dd in range(len(dates)):
            gs = byday.get(dd, [])
            if not gs:
                continue
            t = m.new_bool_var("")
            worked[a, dd] = t
            for g in gs:
                m.add_implication(x[g, a], t)
    if "days" in RULES or "weekly" in RULES:
        weeks = collections.defaultdict(list)
        for dd, ds in enumerate(dates):
            weeks[datetime.date.fromisoformat(ds).isocalendar()[:2]].append(dd)
        for a in range(N):
            for wk, days in weeks.items():
                if "days" in RULES:
                    ts = [worked[a, dd] for dd in days if (a, dd) in worked]
                    if len(ts) > 6:
                        m.add(sum(ts) <= 6)
    if "rest" in RULES:
        for a in range(N):
            for dd in range(len(dates) - 1):
                if not byday.get(dd) or not byday.get(dd + 1):
                    continue
                S = m.new_int_var(0, 3000, "")  # at most the earliest start worked the next day
                for g in byday[dd + 1]:
                    m.add(S <= slots[g][0][1]).only_enforce_if(x[g, a])
                for g in byday[dd]:
                    m.add(S >= slots[g][0][2] - (1440 - rest)).only_enforce_if(x[g, a])
    if "hebdo" in RULES:
        # reposHebdomadaireMinimal, STRICTER than the rule (so a plan found is a real plan, an infeasibility
        # proves nothing about the real rule): a whole free day r per full ISO week, with 35 h between the
        # last seat of day r-1 and the first of day r+1, i.e. end - start <= 1440 + 1440 - 2100 = 780.
        wk_of = collections.defaultdict(list)
        for dd, ds in enumerate(dates):
            wk_of[datetime.date.fromisoformat(ds).isocalendar()[:2]].append(dd)
        need_h = 35 * 60
        for a in range(N):
            for days in wk_of.values():
                if len(days) < 7:
                    continue
                rs = []
                for r in days:
                    R = m.new_bool_var("")
                    rs.append(R)
                    if (a, r) in worked:
                        m.add_implication(R, worked[a, r].negated())
                    S2 = m.new_int_var(0, 3000, "")
                    for g in byday.get(r + 1, []):
                        m.add(S2 <= slots[g][0][1]).only_enforce_if(x[g, a])
                    for g in byday.get(r - 1, []):
                        m.add(S2 >= slots[g][0][2] - (2880 - need_h)).only_enforce_if([x[g, a], R])
                m.add_bool_or(rs)
    overs = collections.defaultdict(list)
    if "continu" in RULES:
        # travailContinuMax (adult reading): a stretch (holes shorter than the break do not split it) past `shiftMax`
        # owes a break at its sixth hour, which must be relayed.
        L = P["shiftMax"] // CELL
        assert pause == CELL
        for a in range(N):
            for dd in range(len(dates)):
                gs = byday.get(dd, [])
                if not gs:
                    continue
                lo = min(slots[g][0][1] for g in gs) // CELL
                hi = max(slots[g][0][2] for g in gs) // CELL
                busy, unrel = {}, {}
                for c in range(lo - 1, hi + 1):
                    t0, t1 = c * CELL, (c + 1) * CELL
                    cov = [x[g, a] for g in gs if slots[g][0][1] < t1 and slots[g][0][2] > t0]
                    nrcov = [x[g, a] for g in gs if slots[g][0][1] < t1 and slots[g][0][2] > t0 and t0 in slots[g][2]]
                    b = m.new_bool_var("")
                    if cov:
                        m.add(sum(cov) == b)
                    else:
                        m.add(b == 0)
                    busy[c] = b
                    u = m.new_bool_var("")
                    if nrcov:
                        m.add(sum(nrcov) == u)
                    else:
                        m.add(u == 0)
                    unrel[c] = u
                for c in range(lo, hi - L):
                    ov = m.new_bool_var("")  # a stretch starting at c reaches past the cap: one break owed at c+L
                    m.add_bool_and([busy[c - 1].negated()] + [busy[k] for k in range(c, c + L + 1)]).only_enforce_if(ov)
                    m.add_bool_or([busy[c - 1]] + [busy[k].negated() for k in range(c, c + L + 1)]).only_enforce_if(ov.negated())
                    overs[a, dd].append(ov)
                    m.add_bool_or([ov.negated(), unrel[c + L].negated()])
    # Effective work (LegalConstraints.effectiveWorkMinutes): the seats' minutes less the break a stretch past the cap
    # deducts. Without the stretch model the break is credited to everybody (looser, still a relaxation).
    exact_work = "continu" in RULES
    cap_day = 600 if exact_work else dayCap
    cap_week = P["weekMax"] if exact_work else weekCap
    def eff(a, dd):
        return sum(x[g, a] * (slots[g][0][2] - slots[g][0][1]) for g in byday.get(dd, [])) - pause * sum(overs[a, dd])
    if "daily" in RULES:
        for a in range(N):
            for dd in byday:
                m.add(eff(a, dd) <= cap_day)
    if "weekly" in RULES:
        wks = collections.defaultdict(list)
        for dd, ds in enumerate(dates):
            wks[datetime.date.fromisoformat(ds).isocalendar()[:2]].append(dd)
        for a in range(N):
            for days in wks.values():
                m.add(sum(eff(a, dd) for dd in days if dd in byday) <= cap_week)
    if "meal" in RULES:
        # coupureRepasObligatoire, exact on a 30-minute grid: whoever starts a seat before the window and
        # ends one after it leaves a free stretch of `meal` minutes inside it (CoupureRepas.of).
        mw = P["mealWindows"]
        cell = 30
        need = P["meal"] // cell
        for lo, hi in ((mw[0], mw[1]), (mw[2], mw[3])):
            assert lo % cell == 0 and hi % cell == 0 and all(g[0][1] % cell == 0 and g[0][2] % cell == 0 for g in slots)
            ncell = (hi - lo) // cell
            for a in range(N):
                for dd in range(len(dates)):
                    gs = byday.get(dd, [])
                    before = [x[g, a] for g in gs if slots[g][0][1] < lo]
                    after = [x[g, a] for g in gs if slots[g][0][2] > hi]
                    if not before or not after:
                        continue
                    occ = []
                    for k in range(ncell):
                        c0, c1 = lo + k * cell, lo + (k + 1) * cell
                        cov = [x[g, a] for g in gs if slots[g][0][1] < c1 and slots[g][0][2] > c0]
                        c = m.new_bool_var("")
                        if cov:
                            m.add(sum(cov) == c)  # no overlap: at most one covers a cell
                        else:
                            m.add(c == 0)
                        occ.append(c)
                    bv, av = m.new_bool_var(""), m.new_bool_var("")
                    for v in before:
                        m.add_implication(v, bv)
                    for v in after:
                        m.add_implication(v, av)
                    fs = []
                    for p in range(ncell - need + 1):
                        f = m.new_bool_var("")
                        for k in range(p, p + need):
                            m.add_implication(f, occ[k].negated())
                        fs.append(f)
                    m.add_bool_or(fs + [bv.negated(), av.negated()])
    if minimize:
        used = [m.new_bool_var("") for _ in range(N)]
        for a in range(N):
            for g in range(len(slots)):
                m.add_implication(x[g, a], used[a])
        for a in range(N - 1):
            m.add_implication(used[a + 1], used[a])
        m.minimize(sum(used))
    # symmetry: the first slot's seats go to the first people
    return m, x


def dump_plan(s, x, N):
    plan = {}
    for g, mem in enumerate(members):
        people = [a for a in range(N) if s.boolean_value(x[g, a])]
        assert len(people) == len(mem), (g, people, len(mem))
        for seat, a in zip(mem, people):
            plan[seat["id"]] = a
    json.dump(plan, open(args.out, "w"))


def run(N, minimize):
    m, x = build(N, minimize)
    s = cp_model.CpSolver()
    s.parameters.max_time_in_seconds = args.seconds
    s.parameters.num_workers = args.workers
    s.parameters.log_search_progress = False
    t0 = time.time()
    st = s.solve(m)
    name = s.status_name(st)
    extra = f" obj={s.objective_value:.0f} bound={s.best_objective_bound:.1f}" if minimize and st in (cp_model.OPTIMAL, cp_model.FEASIBLE) else ""
    print(f"RESULT N={N} {name}{extra} {time.time()-t0:.0f}s", flush=True)
    if args.out and st in (cp_model.OPTIMAL, cp_model.FEASIBLE):
        dump_plan(s, x, N)
    return name


if args.mode == "decide":
    run(args.n, False)
else:
    run(args.max, True)
