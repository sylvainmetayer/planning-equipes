#!/usr/bin/env python3
"""Renumbers the Flyway migrations a branch adds, just before it is merged.

    python3 .github/scripts/renumeroter-migration.py [base]     (default: origin/main)

A branch takes the next migration number when it pushes; another pull request
merged in between may have taken it since. Run on the branch, clean, right
before merging: when the lowest migration the branch adds is no longer above
the highest of the base, every migration it adds moves, in its order, to the
first numbers above that highest, and the references move with it — but only
in the lines the branch itself added, so a line of the base naming the base's
own migration of the same number is left alone:

  - the file names, ``V163__x.sql``, and every ``V163`` token;
  - the Flyway targets of the tests: ``target("163")`` / ``fromVersion("163")``
    follow the migration, and ``target("162")``, the one just before a moved
    migration, becomes the one just before its new number;
  - the fingerprint of the moved file in src/test/resources/migrations-empreintes.txt,
    recomputed, the file put back in version order.

Prints every change and leaves them uncommitted (the renames staged) for the
coordinator to read and commit. Fails, changing nothing, on a dirty tree, on
two added migrations of one number, on a target name already taken, and on an
added line naming the base's migration of a moved number by its file name: it
cannot tell which of the two that line means. A bare ``V163`` the branch wrote
for the base's own V163 is not told apart and moves too: read the printed diff.
"""
import hashlib
import os
import re
import subprocess
import sys
from pathlib import Path

MIGRATIONS = 'src/main/resources/db/migration'
FINGERPRINTS = 'src/test/resources/migrations-empreintes.txt'
MIGRATION = re.compile(r'V(\d+)__.*\.sql$')
TOKEN = re.compile(r'\bV(\d+)(?!\d)')
TARGET = re.compile(r'((?:target|fromVersion)\(\s*")(\d+)(")')
HUNK = re.compile(r'^@@ -\S+ \+(\d+)(?:,(\d+))? @@', re.M)


class Ambiguity(Exception):
    pass


def git(*args):
    return subprocess.run(['git', *args], check=True, capture_output=True, text=True).stdout


def version(name):
    m = MIGRATION.match(Path(name).name)
    return int(m.group(1)) if m else None


def added_lines(base, path):
    """The line numbers (1-based, in HEAD) the branch added to a file."""
    numbers = set()
    for start, count in HUNK.findall(git('diff', '-U0', '--no-renames', f'{base}...HEAD', '--', path)):
        first, n = int(start), int(count or '1')
        numbers.update(range(first, first + n))
    return numbers


def plan(base):
    base_files = [f for f in git('ls-tree', '-r', '--name-only', base, '--', MIGRATIONS).splitlines() if version(f) is not None]
    highest = max(version(f) for f in base_files)
    added = [f for f in git('diff', '--name-only', '--no-renames', '--diff-filter=A', f'{base}...HEAD', '--', MIGRATIONS).splitlines() if version(f) is not None]
    added.sort(key=version)
    versions = [version(f) for f in added]
    if len(set(versions)) != len(versions):
        raise Ambiguity(f'the branch adds two migrations of one number: {", ".join(added)}')
    if not added or versions[0] > highest:
        return highest, {}, {}
    moves = {v: highest + 1 + i for i, v in enumerate(versions)}
    renames = {}
    head = set(git('ls-tree', '-r', '--name-only', 'HEAD', '--', MIGRATIONS).splitlines())
    for f in added:
        target = f'{MIGRATIONS}/V{moves[version(f)]}__{Path(f).name.split("__", 1)[1]}'
        if target in head and target not in added:
            raise Ambiguity(f'{target} already exists')
        renames[f] = target
    return highest, moves, renames


def rewrite(line, moves):
    def token(m):
        n = int(m.group(1))
        return f'V{moves[n]}' if n in moves else m.group(0)

    def target(m):
        n = int(m.group(2))
        if n in moves:
            n = moves[n]
        elif n + 1 in moves:
            n = moves[n + 1] - 1
        return f'{m.group(1)}{n}{m.group(3)}'

    return TARGET.sub(target, TOKEN.sub(token, line))


def main(base):
    os.chdir(git('rev-parse', '--show-toplevel').strip())
    if git('status', '--porcelain', '--untracked-files=no'):
        raise Ambiguity('the working tree has uncommitted changes: commit or stash them first')
    git('rev-parse', '--verify', '--quiet', f'{base}^{{commit}}')
    highest, moves, renames = plan(base)
    if not moves:
        print(f'renumeroter-migration: nothing to move, no migration of this branch is at or below V{highest} of {base}')
        return
    # The base's own migrations of a moved number, by file name: a line naming one is ambiguous
    taken = {Path(f).name for f in git('ls-tree', '-r', '--name-only', base, '--', MIGRATIONS).splitlines() if version(f) in moves}
    touched = git('diff', '--name-only', '--no-renames', '--diff-filter=AM', f'{base}...HEAD').splitlines()
    edits = {}
    for path in touched:
        try:
            # newline='': a CRLF file keeps its line endings, only the changed lines move
            lines = Path(path).read_text(encoding='utf-8', newline='').split('\n')
        except UnicodeDecodeError:
            continue
        changed = []
        for number in sorted(added_lines(base, path)):
            old = lines[number - 1]
            clash = next((name for name in taken if name in old), None)
            if clash:
                raise Ambiguity(f'{path}:{number} names {clash}, the migration of {base} under that number')
            new = rewrite(old, moves)
            if new != old:
                lines[number - 1] = new
                changed.append((number, old, new))
        if changed:
            edits[path] = (lines, changed)
    # Nothing is written before every file has been read without an ambiguity
    for path, (lines, changed) in edits.items():
        Path(path).write_text('\n'.join(lines), encoding='utf-8', newline='')
        for number, old, new in changed:
            print(f'{path}:{number}\n  - {old.strip()}\n  + {new.strip()}')
    for old, new in renames.items():
        git('mv', old, new)
        print(f'renamed {old} -> {new}')
    fingerprints(renames)
    print(f'renumeroter-migration: {len(renames)} migration(s) moved above V{highest} of {base}; read `git diff HEAD`, then commit')


def fingerprints(renames):
    path = Path(FINGERPRINTS)
    if not path.exists():
        return
    header, rows = [], []
    for line in path.read_text(encoding='utf-8').splitlines():
        (header if not rows and (line.startswith('#') or not line.strip()) else rows).append(line)
    moved = {Path(new).name: hashlib.sha256(Path(new).read_bytes()).hexdigest() for new in renames.values()}
    out = []
    for row in rows:
        if not row.strip():
            continue
        name = row.split()[0]
        if name in moved and row.split()[1] != moved[name]:
            row = f'{name} {moved[name]}'
            print(f'{FINGERPRINTS}: {name} fingerprint recomputed')
        out.append(row)
    out.sort(key=lambda r: version(r.split()[0]) or 0)
    path.write_text('\n'.join(header + out) + '\n', encoding='utf-8')


if __name__ == '__main__':
    try:
        main(sys.argv[1] if len(sys.argv) > 1 else 'origin/main')
    except Ambiguity as e:
        print(f'renumeroter-migration: {e} — nothing changed', file=sys.stderr)
        sys.exit(1)
    except subprocess.CalledProcessError as e:
        print(f'renumeroter-migration: `{" ".join(e.cmd)}` failed: {e.stderr.strip()}', file=sys.stderr)
        sys.exit(1)
