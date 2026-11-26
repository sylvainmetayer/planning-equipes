#!/usr/bin/env python3
"""renumeroter-migration.py, played on a throwaway git repository.

    python3 .github/scripts/test_renumeroter_migration.py
"""
import hashlib
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parent / 'renumeroter-migration.py'
MIG = 'src/main/resources/db/migration'
FP = 'src/test/resources/migrations-empreintes.txt'
# The machine's git configuration (signing, hooks, identity) stays out of it
ENV = {**os.environ, 'GIT_CONFIG_GLOBAL': os.devnull, 'GIT_CONFIG_NOSYSTEM': '1',
       'GIT_AUTHOR_NAME': 't', 'GIT_AUTHOR_EMAIL': 't@example.org',
       'GIT_COMMITTER_NAME': 't', 'GIT_COMMITTER_EMAIL': 't@example.org'}


def digest(text):
    return hashlib.sha256(text.encode()).hexdigest()


class Renumbering(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.repo = Path(self.tmp.name)
        self.git('init', '-q', '-b', 'main')
        self.write(f'{MIG}/V1__a.sql', 'select 1;\n')
        self.write(f'{MIG}/V2__b.sql', 'select 2;\n')
        self.write(FP, f'# header\nV1__a.sql {digest("select 1;\n")}\nV2__b.sql {digest("select 2;\n")}\n')
        self.write('docs/guide.md', 'Intro.\n')
        # As in the repository: two branches each appending a fingerprint do not conflict
        self.write('.gitattributes', f'{FP} merge=union\n')
        self.commit('base')
        # The branch takes the next number, V3
        self.git('checkout', '-q', '-b', 'branche')
        self.write(f'{MIG}/V3__mine.sql', '-- V3: mine\nselect 3;\n')
        self.append(FP, f'V3__mine.sql {digest("-- V3: mine\nselect 3;\n")}\n')
        self.write('src/test/MineTest.java',
                   'class MineTest {\n'
                   '    static final String V3 = "db/migration/V3__mine.sql";\n'
                   '    void before() { flyway.target("2"); }\n'
                   '    void filter() { MigrationVersion.fromVersion("3"); }\n'
                   '}\n')
        self.append('docs/guide.md', 'Migration `V3` converts it.\n')
        self.commit('mine')
        # Meanwhile main takes V3 and V4
        self.git('checkout', '-q', 'main')
        self.write(f'{MIG}/V3__theirs.sql', 'select 30;\n')
        self.write(f'{MIG}/V4__theirs.sql', 'select 40;\n')
        self.append(FP, f'V3__theirs.sql {digest("select 30;\n")}\nV4__theirs.sql {digest("select 40;\n")}\n')
        self.write('docs/other.md', 'Their `V3`.\n')
        self.commit('theirs')
        self.git('checkout', '-q', 'branche')

    def tearDown(self):
        self.tmp.cleanup()

    def git(self, *args):
        return subprocess.run(['git', *args], cwd=self.repo, env=ENV, check=True, capture_output=True, text=True).stdout

    def write(self, path, text):
        (self.repo / path).parent.mkdir(parents=True, exist_ok=True)
        (self.repo / path).write_text(text)

    def append(self, path, text):
        self.write(path, self.read(path) + text)

    def read(self, path):
        return (self.repo / path).read_text()

    def commit(self, message):
        self.git('add', '-A')
        self.git('commit', '-q', '-m', message)

    def run_script(self, base='main'):
        return subprocess.run([sys.executable, str(SCRIPT), base], cwd=self.repo, env=ENV, capture_output=True, text=True)

    def test_refuses_a_base_that_is_not_a_ref_name(self):
        result = self.run_script('--output=/tmp/x')
        self.assertEqual(result.returncode, 1)
        self.assertIn('not a branch or commit name', result.stderr)

    def test_moves_the_migration_and_its_references_above_the_base(self):
        result = self.run_script()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertFalse((self.repo / f'{MIG}/V3__mine.sql').exists())
        self.assertEqual(self.read(f'{MIG}/V5__mine.sql'), '-- V5: mine\nselect 3;\n')
        java = self.read('src/test/MineTest.java')
        self.assertIn('"db/migration/V5__mine.sql"', java)
        self.assertIn('static final String V5 =', java)
        self.assertIn('target("4")', java, 'the target just before the migration follows it')
        self.assertIn('fromVersion("5")', java)
        self.assertEqual(self.read('docs/guide.md'), 'Intro.\nMigration `V5` converts it.\n')
        self.assertEqual(self.read(FP).splitlines()[-1], f'V5__mine.sql {digest("-- V5: mine\nselect 3;\n")}')
        self.assertIn('renamed', result.stdout)

    def test_leaves_the_lines_of_the_base_alone_once_main_is_merged(self):
        self.git('merge', '-q', '--no-edit', 'main')
        result = self.run_script()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.read('docs/other.md'), 'Their `V3`.\n')
        self.assertTrue((self.repo / f'{MIG}/V3__theirs.sql').exists())
        rows = [r.split()[0] for r in self.read(FP).splitlines() if not r.startswith('#')]
        self.assertEqual(rows, ['V1__a.sql', 'V2__b.sql', 'V3__theirs.sql', 'V4__theirs.sql', 'V5__mine.sql'])

    def test_does_nothing_when_the_branch_is_already_above(self):
        self.git('checkout', '-q', 'main')
        self.git('checkout', '-q', '-b', 'later')
        self.write(f'{MIG}/V5__later.sql', 'select 5;\n')
        self.commit('later')
        result = self.run_script()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('nothing to move', result.stdout)
        self.assertEqual(self.git('status', '--porcelain'), '')

    def test_refuses_a_line_naming_the_base_migration_of_that_number(self):
        self.append('docs/guide.md', 'Unlike V3__theirs.sql.\n')
        self.commit('ambiguous')
        result = self.run_script()
        self.assertEqual(result.returncode, 1)
        self.assertIn('V3__theirs.sql', result.stderr)
        self.assertEqual(self.git('status', '--porcelain'), '', 'nothing changed')

    def test_refuses_a_dirty_tree(self):
        self.append('docs/guide.md', 'Uncommitted.\n')
        result = self.run_script()
        self.assertEqual(result.returncode, 1)
        self.assertIn('uncommitted', result.stderr)

    def test_moves_two_migrations_in_their_order_without_chaining(self):
        self.write(f'{MIG}/V4__mine_too.sql', 'select 4;\n')
        self.append('docs/guide.md', 'Then `V4` after `V3`.\n')
        self.commit('two')
        result = self.run_script()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue((self.repo / f'{MIG}/V5__mine.sql').exists())
        self.assertTrue((self.repo / f'{MIG}/V6__mine_too.sql').exists())
        self.assertIn('Then `V6` after `V5`.', self.read('docs/guide.md'))


if __name__ == '__main__':
    unittest.main()
