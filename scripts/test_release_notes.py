"""
Tests for release-notes.py, against real git repositories made for each test.

    python3 -m unittest discover -s scripts

Real repositories because the parsing that matters is git's: what counts as a
trailer, how a merge's commits are found. A test that fed the functions strings
would test the half that is not in doubt.
"""

from __future__ import annotations

import importlib.util
import json
import os
import subprocess
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
_spec = importlib.util.spec_from_file_location('release_notes', HERE / 'release-notes.py')
notes = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(notes)


class Repository:
    """A git repository in a temporary directory, with commits made to order."""

    def __init__(self, directory: str):
        self.directory = directory
        self.git('init', '-q', '-b', 'main')
        self.git('config', 'user.email', 'test@example.invalid')
        self.git('config', 'user.name', 'Test')
        self.git('config', 'commit.gpgsign', 'false')
        self.commit('Start\n\nRelease-Note: none')

    def git(self, *arguments: str) -> str:
        return subprocess.run(
            ('git',) + arguments, cwd=self.directory, check=True, capture_output=True, text=True,
        ).stdout

    def commit(self, message: str) -> str:
        self.git('commit', '-q', '--allow-empty', '-m', message)
        return self.head()

    def head(self) -> str:
        return self.git('rev-parse', 'HEAD').strip()


class ReleaseNotesTest(unittest.TestCase):

    def setUp(self):
        self._directory = tempfile.TemporaryDirectory()
        self.repo = Repository(self._directory.name)
        self._cwd = os.getcwd()
        # The script runs git in the working directory, as CI runs it.
        os.chdir(self._directory.name)

    def tearDown(self):
        os.chdir(self._cwd)
        self._directory.cleanup()

    def collect_since(self, start: str):
        return notes.collect(start, self.repo.head())

    def test_a_plain_note_is_an_improvement_to_both_apps(self):
        start = self.repo.head()
        self.repo.commit('Work\n\nRelease-Note: Outfits keep their rating when edited.')

        found, silent, problems = self.collect_since(start)

        self.assertEqual(
            [{'text': 'Outfits keep their rating when edited.', 'kind': 'improved', 'platforms': ['android', 'web']}],
            found,
        )
        self.assertEqual([], silent)
        self.assertEqual([], problems)

    def test_brackets_say_the_kind_the_apps_and_where_to_look(self):
        start = self.repo.head()
        self.repo.commit(
            'Work\n\n'
            'Release-Note: [new android -> settings] Sync with Home Assistant.\n'
            'Release-Note: [fixed web] The browser keeps its place.\n'
            'Release-Note: [android new → garment.bulk] Add many at once.'
        )

        found, _, problems = self.collect_since(start)

        self.assertEqual([
            {'text': 'Sync with Home Assistant.', 'kind': 'new', 'platforms': ['android'], 'to': 'settings'},
            {'text': 'The browser keeps its place.', 'kind': 'fixed', 'platforms': ['web']},
            {'text': 'Add many at once.', 'kind': 'new', 'platforms': ['android'], 'to': 'garment.bulk'},
        ], found)
        self.assertEqual([], problems)

    def test_what_cannot_be_read_is_reported_and_costs_only_itself(self):
        start = self.repo.head()
        self.repo.commit(
            'Work\n\n'
            'Release-Note: [nwe android -> nowhere] Something new.'
        )

        found, _, problems = self.collect_since(start)

        # The note survives; the typo and the unknown destination are named.
        self.assertEqual([{'text': 'Something new.', 'kind': 'improved', 'platforms': ['android']}], found)
        self.assertEqual(2, len(problems), problems)
        self.assertIn('"nwe"', problems[0])
        self.assertIn('"nowhere"', problems[1])

    def test_spanish_pairs_with_english_by_position_skipping_none(self):
        start = self.repo.head()
        self.repo.commit(
            'Work\n\n'
            'Release-Note: none\n'
            'Release-Note: [new] First.\n'
            'Release-Note: Second.\n'
            'Release-Note-es: Primero.\n'
            'Release-Note-es: Segundo.'
        )

        found, _, problems = self.collect_since(start)

        self.assertEqual(['Primero.', 'Segundo.'], [note['text_es'] for note in found])
        self.assertEqual([], problems)

    def test_spanish_is_paired_within_its_own_commit(self):
        start = self.repo.head()
        self.repo.commit('One\n\nRelease-Note: First.')
        self.repo.commit('Two\n\nRelease-Note: Second.\nRelease-Note-es: Segundo.')

        found, _, problems = self.collect_since(start)

        # Newest first, as the published history is.
        by_text = {note['text']: note for note in found}
        self.assertNotIn('text_es', by_text['First.'], 'a commit without Spanish took the next commit\'s')
        self.assertEqual('Segundo.', by_text['Second.']['text_es'])
        self.assertEqual([], problems)

    def test_a_count_that_does_not_match_is_reported(self):
        start = self.repo.head()
        self.repo.commit('Work\n\nRelease-Note: First.\nRelease-Note: Second.\nRelease-Note-es: Primero.')

        found, _, problems = self.collect_since(start)

        self.assertEqual('Primero.', found[0]['text_es'])
        self.assertNotIn('text_es', found[1])
        self.assertEqual(1, len(problems))

    def test_a_note_quoted_in_prose_is_not_a_note(self):
        start = self.repo.head()
        self.repo.commit(
            'Explain the convention\n\n'
            'Write it like this:\n\n'
            '    Release-Note: [new] Not a real note.\n\n'
            'and that is all.\n\n'
            'Release-Note: none'
        )

        found, silent, _ = self.collect_since(start)

        self.assertEqual([], found)
        self.assertEqual([], silent)

    def test_a_merge_answers_for_the_commits_it_brings(self):
        start = self.repo.head()
        self.repo.git('checkout', '-q', '-b', 'branch')
        self.repo.commit('Branch work\n\nRelease-Note: [fixed] From the branch.')
        self.repo.git('checkout', '-q', 'main')
        self.repo.git('merge', '-q', '--no-ff', '-m', 'Merge branch', 'branch')
        self.repo.commit('Straight to main, saying nothing')

        found, silent, _ = self.collect_since(start)

        self.assertEqual(['From the branch.'], [note['text'] for note in found])
        self.assertEqual(['Straight to main, saying nothing'], silent)

    def test_phones_already_installed_get_their_own_lines_as_strings(self):
        found = [
            {'text': 'Phone and browser.', 'kind': 'improved', 'platforms': ['android', 'web']},
            {'text': 'Browser only.', 'kind': 'fixed', 'platforms': ['web']},
        ]
        self.assertEqual(['Phone and browser.'], notes.for_phones_installed(found))
        self.assertEqual([notes.NOTHING], notes.for_phones_installed(found[1:]))

    def test_history_carries_old_documents_forward_in_the_new_shape(self):
        # A document from before history existed: its changes are its build's.
        legacy = {'version_code': 1300, 'changes': ['Old line.', notes.NOTHING]}
        self.assertEqual(
            [{'build': 1300, 'text': 'Old line.', 'kind': 'improved', 'platforms': ['android']}],
            notes.carried_history(legacy),
        )

        # One from before kinds: about the phone, the only app there was.
        plain = {'history': [{'build': 1310, 'text': 'Plain.'}]}
        self.assertEqual(
            [{'build': 1310, 'text': 'Plain.', 'kind': 'improved', 'platforms': ['android']}],
            notes.carried_history(plain),
        )

        # And today's, kept whole, with anything unreadable falling back.
        structured = {'history': [
            {'build': 1320, 'text': 'New.', 'kind': 'new', 'platforms': ['web'], 'text_es': 'Nuevo.', 'to': 'settings'},
            {'build': 1321, 'text': 'Odd.', 'kind': 'shiny', 'platforms': ['fridge'], 'to': 'nowhere'},
        ]}
        self.assertEqual([
            {'build': 1320, 'text': 'New.', 'kind': 'new', 'platforms': ['web'], 'text_es': 'Nuevo.', 'to': 'settings'},
            {'build': 1321, 'text': 'Odd.', 'kind': 'improved', 'platforms': ['android']},
        ], notes.carried_history(structured))

    def test_history_puts_this_build_first_and_drops_what_claims_to_be_newer(self):
        previous = {'history': [
            {'build': 1400, 'text': 'From the future.'},
            {'build': 1390, 'text': 'Before.'},
        ]}
        own = [{'text': 'Now.', 'kind': 'new', 'platforms': ['android']}]

        history = notes.history_for(1400, own, previous)

        self.assertEqual(['Now.', 'Before.'], [entry['text'] for entry in history])
        self.assertEqual(1400, history[0]['build'])

    def test_the_home_assistant_changelog_lists_the_browser_notes_new_first(self):
        found = [
            {'text': 'A fix.', 'kind': 'fixed', 'platforms': ['web']},
            {'text': 'Phone only.', 'kind': 'new', 'platforms': ['android']},
            {'text': 'Both.', 'kind': 'improved', 'platforms': ['android', 'web']},
            {'text': 'Brand new.', 'kind': 'new', 'platforms': ['web']},
        ]
        self.assertEqual('- Brand new.\n- Both.\n- A fix.\n', notes.home_assistant_changelog(found))
        self.assertEqual(f'- {notes.NOTHING}\n', notes.home_assistant_changelog(found[1:2]))

    def test_the_document_is_written_as_json_the_app_reads(self):
        start = self.repo.head()
        self.repo.commit('Work\n\nRelease-Note: [new android] Línea.\nRelease-Note-es: Línea en español.')
        script = str(HERE / 'release-notes.py')

        printed = subprocess.run(
            ['python3', script, start, self.repo.head(), '--history', '1500'],
            check=True, capture_output=True, text=True,
        ).stdout

        self.assertEqual(
            [{'build': 1500, 'text': 'Línea.', 'kind': 'new', 'platforms': ['android'], 'text_es': 'Línea en español.'}],
            json.loads(printed),
        )


if __name__ == '__main__':
    unittest.main()
