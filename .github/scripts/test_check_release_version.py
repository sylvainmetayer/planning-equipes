#!/usr/bin/env python3
"""check-release-version.sh, played on a throwaway git repository.

    python3 .github/scripts/test_check_release_version.py
"""
import os
import subprocess
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parent / 'check-release-version.sh'
# The machine's git configuration (signing, hooks, identity) stays out of it
ENV = {**os.environ, 'GIT_CONFIG_GLOBAL': os.devnull, 'GIT_CONFIG_NOSYSTEM': '1',
       'GIT_AUTHOR_NAME': 't', 'GIT_AUTHOR_EMAIL': 't@example.org',
       'GIT_COMMITTER_NAME': 't', 'GIT_COMMITTER_EMAIL': 't@example.org'}


class CheckReleaseVersion(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.repo = self.tmp.name
        self.git('init', '-q', '-b', 'main')
        self.commit('feat: base')
        self.git('tag', 'v3.5.0')

    def tearDown(self):
        self.tmp.cleanup()

    def git(self, *args):
        subprocess.run(['git', *args], cwd=self.repo, env=ENV, check=True, capture_output=True)

    def commit(self, subject):
        self.git('commit', '-q', '--allow-empty', '-m', subject)

    def tag(self, subject, tag):
        self.commit(subject)
        self.git('tag', tag)

    def check(self, tag):
        # GitHub's default shell, the one that used to stop the step without a word
        return subprocess.run(['bash', '-e', str(SCRIPT), tag], cwd=self.repo, env=ENV,
                              capture_output=True, text=True)

    def assertAccepted(self, tag):
        r = self.check(tag)
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        self.assertNotIn('::error::', r.stdout)

    def assertRefused(self, tag, reason):
        r = self.check(tag)
        self.assertEqual(r.returncode, 1, r.stdout + r.stderr)
        self.assertIn('::error::', r.stdout)
        self.assertIn(reason, r.stdout)

    def test_first_rc_is_compared_with_the_last_final_release(self):
        self.tag('feat!: rupture', 'v4.0.0-rc.1')
        self.assertAccepted('v4.0.0-rc.1')

    def test_next_rc_must_grow(self):
        self.tag('feat!: rupture', 'v4.0.0-rc.1')
        self.tag('fix: a', 'v4.0.0-rc.2')
        self.assertAccepted('v4.0.0-rc.2')
        self.tag('fix: b', 'v4.0.0-rc.10')
        self.assertAccepted('v4.0.0-rc.10')  # numeric, not lexical

    def test_rc_below_an_existing_rc_is_refused(self):
        self.tag('feat!: rupture', 'v4.0.0-rc.2')
        self.tag('fix: a', 'v4.0.0-rc.1')
        self.assertRefused('v4.0.0-rc.1', 'doit croître')

    def test_rc_of_a_published_version_is_refused(self):
        self.tag('feat!: rupture', 'v4.0.0')
        self.tag('fix: a', 'v4.0.0-rc.1')
        self.assertRefused('v4.0.0-rc.1', 'déjà publiée')

    def test_rc_must_carry_the_bump_its_subjects_ask(self):
        self.tag('feat: nouveau', 'v3.5.1-rc.1')
        self.assertRefused('v3.5.1-rc.1', 'demandaient MINOR')

    def test_final_release_after_its_rcs(self):
        self.tag('feat!: rupture', 'v4.0.0-rc.1')
        self.tag('fix: a', 'v4.0.0')
        self.assertAccepted('v4.0.0')

    def test_release_not_above_the_previous_one_is_refused(self):
        self.tag('fix: a', 'v3.4.9')
        self.assertRefused('v3.4.9', "n'est pas supérieur")

    def test_first_release_of_a_repository(self):
        self.git('tag', '-d', 'v3.5.0')
        self.tag('feat: a', 'v1.0.0-rc.1')
        self.assertAccepted('v1.0.0-rc.1')

    def test_malformed_tag_is_refused(self):
        self.assertRefused('v4.0.0-beta.1', "n'est pas un tag de release")


if __name__ == '__main__':
    unittest.main()
