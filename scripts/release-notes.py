#!/usr/bin/env python3
"""
What to tell somebody about a build, as opposed to what changed in it.

    python3 scripts/release-notes.py <previous-commit> <this-commit>
    python3 scripts/release-notes.py <previous-commit> <this-commit> --history <build> [<previous-document>]
    python3 scripts/release-notes.py <previous-commit> <this-commit> --home-assistant
    python3 scripts/release-notes.py <previous-commit> <this-commit> --web-history

Prints a JSON array of lines for the update dialog of phones already installed,
and says on stderr which merges had nothing to contribute and did not say so,
and which notes it could not read.

With --history it prints every note as a structured entry tagged with the
build it arrives in, followed by the notes the previous published document
carried, so a phone several builds behind can be told everything since its own
build rather than since the last one published. See `history_for` below.

With --home-assistant it prints the notes for the browser as a Markdown list,
for the Home Assistant app's CHANGELOG.md -- see `home_assistant_changelog`.

With --web-history it prints the browser's notes grouped by the version of the
Home Assistant app that first carried each, for the browser's What's new -- see
`web_history`.

The changelog used to be the list of pull request titles in the range, which is
how it ended up telling people about "Build each branch once, in one pass, and
fetch the history the changelog needs". A pull request title is written for
somebody reading the diff: it names the change. A changelog line is read by
somebody deciding whether to install, and has to name what is different for them.
Those are different sentences, and no amount of care with the first one produces
the second -- a purely internal change has no second sentence at all, and the old
scheme had no way to say so.

So the line is written by hand, in the commit, as a trailer:

    Release-Note: Outfit photos now open from the grid rather than jumping.

and a change with nothing to say says that instead:

    Release-Note: none

A note can say what kind of change it is, which of the two apps it is about,
and where in the app it can be seen, in brackets before the text; and it can
carry its Spanish beside it, as a second trailer:

    Release-Note: [new android -> settings] Your wardrobe can sync with Home Assistant.
    Release-Note-es: Tu armario puede sincronizarse con Home Assistant.

The kind is `new`, `improved` or `fixed`; the apps are `android` and `web` (the
browser, in Home Assistant); the destination is one of DESTINATIONS. Each part
is optional, and a note with no brackets at all is an improvement to both apps,
which is what every note written before this grammar existed reads as. The
Spanish pairs with the English by position within a commit -- the second
Release-Note-es with the second Release-Note that is not `none` -- and a note
without one shows its English to somebody reading in Spanish. See `parse_note`.

It goes in the trailer block at the end of the message, beside Co-Authored-By and
the rest -- git's own definition of a trailer, so that a message which merely
mentions `Release-Note:` in its prose, as this project's own commits now do, is
not read as carrying one.

The trailer is looked for in every commit a merge brings in, not only in the merge
itself, so it can be written when the work is done rather than remembered at merge
time. Several are allowed, and become several lines. A merge with no trailer at
all contributes nothing and is named in the warning, because silence and "nothing
to say" look identical in a changelog and only one of them is deliberate.
"""

from __future__ import annotations

import json
import re
import subprocess
import sys

KEY = 'Release-Note'
SPANISH_KEY = 'Release-Note-es'

KINDS = ('new', 'improved', 'fixed')
PLATFORMS = ('android', 'web')

# Where a note can send somebody, once they have the build it describes. The app
# maps each to a screen; ReleaseNoteDestinationsTest holds this list and the
# app's AppDestination to each other, so a destination one side knows and the
# other does not cannot be published.
DESTINATIONS = (
    'home',
    'wardrobe',
    'outfits',
    'statistics',
    'settings',
    'garment.new',
    'garment.bulk',
    'outfit.new',
)

# What a note written before kinds existed, or without brackets, is.
DEFAULT_KIND = 'improved'

# What a note carried from a document written before platforms existed is about:
# the phone, which was the only app there was.
LEGACY_PLATFORMS = ['android']

NONE_VALUES = ('none', 'n/a', 'no', '-')

TAGS = re.compile(r'^\[([^\]]*)\]\s*(.*)$', re.S)

# What a build says for itself when nothing in it was worth a line. Honest rather
# than cheerful: the alternative is inventing a feature, and somebody deciding
# whether to spend a download on this is better served by being told there is
# nothing in it for them.
NOTHING = 'Fixes and groundwork. Nothing you should notice.'

# The dialog shows eight and the document is fetched by every phone at every
# launch, so there is no reason to carry a hundred.
LIMIT = 50


def git(*arguments: str) -> str:
    return subprocess.run(
        ('git',) + arguments, check=True, capture_output=True, text=True).stdout


def parse_note(value: str, problems: list[str]) -> dict | None:
    """
    One Release-Note trailer as an entry, or None for an empty one.

    `[kind platform... -> destination] text`, every part of the brackets
    optional, in any order but the destination last. A word in the brackets that
    is none of those is reported in [problems] and otherwise ignored -- a typo
    should cost a warning on the release run, not the note -- and so is a
    destination the app does not know, since a link that goes nowhere is worse
    than no link.

    `->` or `→` both, because the second is what the plan wrote and the first is
    what a keyboard has.
    """
    text = value.strip()
    if not text:
        return None

    kind, platforms, destination = DEFAULT_KIND, list(PLATFORMS), None
    match = TAGS.match(text)
    if match:
        tags, text = match.group(1).replace('→', '->'), match.group(2).strip()
        words, _, target = tags.partition('->')
        named_kinds = [word for word in words.split() if word in KINDS]
        named_platforms = [word for word in words.split() if word in PLATFORMS]
        for word in words.split():
            if word not in KINDS and word not in PLATFORMS:
                problems.append(f'"{word}" is not a kind or an app, in: {value.strip()}')
        if len(named_kinds) > 1:
            problems.append(f'more than one kind, the first is used, in: {value.strip()}')
        if named_kinds:
            kind = named_kinds[0]
        if named_platforms:
            platforms = [platform for platform in PLATFORMS if platform in named_platforms]
        target = target.strip()
        if target:
            if target in DESTINATIONS:
                destination = target
            else:
                problems.append(f'"{target}" is not a destination the app knows, in: {value.strip()}')
        if not text:
            problems.append(f'a note with nothing after its brackets: {value.strip()}')
            return None

    entry = {'text': text, 'kind': kind, 'platforms': platforms}
    if destination:
        entry['to'] = destination
    return entry


def trailer_values(commit: str, key: str) -> list[str]:
    """
    Every value of the trailer [key] on [commit], in order.

    Git does the parsing rather than a search for lines starting with the key,
    which is what this did first and which is wrong in a way that took a commit
    about trailers to notice: that commit quoted `Release-Note:` twice as an
    example, indented in its own prose, and both examples went into the changelog.
    A trailer is only a trailer in the block at the end of the message, and
    `%(trailers:key=...)` is git's own rule for what that means -- the same rule
    that decides whether Co-Authored-By counts.
    """
    values = git('log', '-1', f'--format=%(trailers:key={key},valueonly,unfold)', commit)
    return [value.strip() for value in values.splitlines() if value.strip()]


def notes_in(commits: list[str], problems: list[str]) -> tuple[list[dict], bool]:
    """
    Every release note across these commits, and whether any of them said anything.

    The second half of that is not just "the list is empty": `Release-Note: none`
    is a decision and an absent trailer is an oversight, and they have to be told
    apart in order to warn about one and not the other.

    The Spanish is paired with the English commit by commit, by position, so a
    branch whose commits each carry their own pair cannot cross them over.
    """
    notes, spoken = [], False
    for commit in commits:
        english = trailer_values(commit, KEY)
        spanish = trailer_values(commit, SPANISH_KEY)
        if english:
            spoken = True
        entries = []
        for value in english:
            if value.lower() in NONE_VALUES:
                continue
            entry = parse_note(value, problems)
            if entry is not None:
                entries.append(entry)
        if spanish and len(spanish) != len(entries):
            subject = git('log', '-1', '--format=%s', commit).strip()
            problems.append(
                f'{len(entries)} note(s) and {len(spanish)} Spanish one(s) in "{subject}", '
                'paired in order as far as they go')
        for entry, translation in zip(entries, spanish):
            # Brackets on the Spanish line say nothing the English one does not.
            match = TAGS.match(translation)
            entry['text_es'] = (match.group(2) if match else translation).strip()
        notes += entries
    return notes, spoken


def carried_history(document: dict) -> list[dict]:
    """
    The notes a previously published document carries forward, each with its build.

    A document written since builds carried their notes has them under `history`.
    One written before has only `changes`, which are exactly the notes of the build
    it described -- so that build is the one they are given. Its placeholder line
    is not a note and stays behind.

    Anything malformed is dropped rather than fatal: a changelog line fewer is not
    worth a release run that fails.
    """
    if not isinstance(document, dict):
        return []

    entries = document.get('history')
    if isinstance(entries, list):
        kept = []
        for entry in entries:
            if not isinstance(entry, dict) or not isinstance(entry.get('text'), str):
                continue
            try:
                build = int(entry['build'])
            except (KeyError, TypeError, ValueError):
                continue
            kept.append(carried_entry(build, entry))
        return kept

    try:
        build = int(document.get('version_code'))
    except (TypeError, ValueError):
        return []
    return [
        carried_entry(build, {'text': text})
        for text in document.get('changes', [])
        if isinstance(text, str) and text != NOTHING
    ]


def carried_entry(build: int, entry: dict) -> dict:
    """
    A carried note in today's shape, whatever shape it was published in.

    A note published before kinds and platforms existed was about the phone,
    the only app there was, and is read as an improvement to it. Anything
    unreadable in a newer one falls back the same way rather than costing the
    note.
    """
    kind = entry.get('kind') if entry.get('kind') in KINDS else DEFAULT_KIND
    platforms = entry.get('platforms')
    if not isinstance(platforms, list) or not platforms or any(p not in PLATFORMS for p in platforms):
        platforms = LEGACY_PLATFORMS
    carried = {'build': build, 'text': entry['text'], 'kind': kind, 'platforms': list(platforms)}
    if isinstance(entry.get('text_es'), str) and entry['text_es'].strip():
        carried['text_es'] = entry['text_es']
    if entry.get('to') in DESTINATIONS:
        carried['to'] = entry['to']
    return carried


def history_for(build: int, notes: list[dict], previous: dict) -> list[dict]:
    """
    This build's notes, then the ones carried from before it, newest first.

    Every merge publishes a build that replaces the last, so each document's own
    notes describe one build's worth of change -- and a phone that skipped a few
    launches was told about the newest build and nothing before it. Carrying the
    earlier notes forward, each with the build it arrived in, lets the phone keep
    those newer than what it runs. The app does that cut; this only records.

    A carried note claiming this build or a later one is dropped: the previous
    document describes something older by definition, and a build number that
    says otherwise is a mistake that would put a note in the wrong place.
    """
    own = [{'build': build, **note} for note in notes]
    older = [entry for entry in carried_history(previous) if entry['build'] < build]
    return (own + older)[:LIMIT]


def landed(previous: str, head: str) -> list[str]:
    """
    What landed on the branch between the two, newest first.

    First parent, so this walks one step per merge rather than every commit
    inside each one.
    """
    return git('log', '--first-parent', '--format=%H', f'{previous}..{head}').split()


def scope_of(commit: str) -> list[str]:
    """
    The commits [commit] answers for: a merge for itself and for everything it
    brought in, a commit pushed straight to the branch for itself alone.
    """
    parents = git('log', '-1', '--format=%P', commit).split()
    scope = [commit]
    if len(parents) > 1:
        scope += git('rev-list', f'{parents[0]}..{parents[1]}').split()
    return scope


# Where the Home Assistant app's version is written; bumping it is what releases
# the app (see CONTRIBUTING.md), so it is also what says which release a note
# arrived in.
HOME_ASSISTANT_CONFIG = 'homeassistant/wardrobapp/config.yaml'
VERSION_LINE = re.compile(r'^version:\s*"?([^"\s]+)"?\s*$', re.M)


def home_assistant_version(commit: str) -> str | None:
    """The Home Assistant app's version at [commit], or None before the app existed."""
    try:
        config = git('show', f'{commit}:{HOME_ASSISTANT_CONFIG}')
    except subprocess.CalledProcessError:
        return None
    match = VERSION_LINE.search(config)
    return match.group(1) if match else None


def web_history(previous: str, head: str) -> list[dict]:
    """
    The browser's notes, by the version of the Home Assistant app that first
    carried each, newest version first:

        [{"version": "0.3.0", "notes": [{"text": ..., "kind": ..., ...}]}, ...]

    The Home Assistant app is released by hand, by bumping the version in its
    config.yaml, and CI publishes an image only for a version not published
    before -- so a version is the image built from the merge that bumped it.
    A note merged after one bump and up to the next is therefore new in the
    next, and that is the rule: walking forward, notes collect until a merge
    changes the version, and go to the version it changed to. Notes after the
    last bump are in no released version yet, and are left out.

    Only notes about the browser, and only versions with something to say.
    Bundled into the image by the release workflow and served to the browser,
    which shows what is newer than the version it last showed.
    """
    version = home_assistant_version(previous)
    pending: list[dict] = []
    releases: dict[str, list[dict]] = {}
    problems: list[str] = []

    for commit in reversed(landed(previous, head)):
        notes, _ = notes_in(scope_of(commit), problems)
        pending += [note for note in notes if 'web' in note['platforms']]
        now = home_assistant_version(commit)
        if now is not None and now != version:
            releases.setdefault(now, []).extend(pending)
            pending = []
            version = now

    history = []
    for name, notes in reversed(list(releases.items())):
        seen = set()
        unique = [note for note in notes if not (note['text'] in seen or seen.add(note['text']))]
        if unique:
            # Newest first within a version too, as everywhere else.
            history.append({'version': name, 'notes': list(reversed(unique))})
    return history


def collect(previous: str, head: str) -> tuple[list[dict], list[str], list[str]]:
    """
    This build's notes, deduplicated in order; the merges that said nothing; and
    the notes that could not be read as written.
    """
    changes: list[dict] = []
    silent: list[str] = []
    problems: list[str] = []

    for commit in landed(previous, head):
        notes, spoken = notes_in(scope_of(commit), problems)
        changes += notes
        if not spoken:
            subject = git('log', '-1', '--format=%s', commit).strip()
            body = git('log', '-1', '--format=%b', commit).strip().splitlines()
            silent.append(body[0] if body else subject)

    # Deduplicated in order, by the English: a trailer written on a branch and
    # repeated in the merge is one line, not two.
    seen = set()
    unique = [note for note in changes if not (note['text'] in seen or seen.add(note['text']))]
    return unique, silent, problems


def for_phones_installed(notes: list[dict]) -> list[str]:
    """
    The `changes` list: English lines, the phone's only.

    Every build already installed reads `changes` and nothing else, as a list of
    strings, so that is what it stays; a note about the browser alone is
    nothing a phone deciding whether to update needs to hear.
    """
    lines = [note['text'] for note in notes if 'android' in note['platforms']]
    return lines[:LIMIT] if lines else [NOTHING]


def home_assistant_changelog(notes: list[dict]) -> str:
    """
    The browser's notes as a Markdown list, for the Home Assistant app's
    CHANGELOG.md, which is what Home Assistant shows before updating it.

    Printed rather than written into the file: the app is released by bumping
    its version by hand, with an entry under that version, and this is the
    first draft of that entry -- run over the range since the last bump, then
    read before it is committed. Fixes last, as people read the list for what
    they gain.
    """
    order = {kind: index for index, kind in enumerate(KINDS)}
    browser = sorted((note for note in notes if 'web' in note['platforms']), key=lambda note: order[note['kind']])
    if not browser:
        return f'- {NOTHING}\n'
    return ''.join(f'- {note["text"]}\n' for note in browser)


def main() -> int:
    arguments = sys.argv[1:]
    usage = '\n'.join(line.strip() for line in __doc__.strip().splitlines()[2:6])

    if len(arguments) == 2:
        mode = 'changes'
    elif len(arguments) == 3 and arguments[2] == '--home-assistant':
        mode = 'home-assistant'
    elif len(arguments) == 3 and arguments[2] == '--web-history':
        print(json.dumps(web_history(arguments[0], arguments[1]), indent=2, ensure_ascii=False))
        return 0
    elif len(arguments) in (4, 5) and arguments[2] == '--history':
        mode = 'history'
    else:
        print(usage, file=sys.stderr)
        return 2

    unique, silent, problems = collect(arguments[0], arguments[1])

    if mode == 'home-assistant':
        print(home_assistant_changelog(unique), end='')
        return 0

    if mode == 'history':
        try:
            build = int(arguments[3])
        except ValueError:
            print(usage, file=sys.stderr)
            return 2
        previous = {}
        if len(arguments) == 5:
            with open(arguments[4], encoding='utf-8') as document:
                previous = json.load(document)
        # No warning here: the same run prints the changes too, and that is the
        # one place a missing trailer should be named.
        print(json.dumps(history_for(build, unique, previous), indent=2, ensure_ascii=False))
        return 0

    if silent:
        print(
            f'::warning::{len(silent)} change(s) in this build carry no Release-Note '
            'trailer, so they are not in the changelog. Add one, or "Release-Note: none" '
            'to say there is nothing to tell: ' + '; '.join(silent),
            file=sys.stderr,
        )
    for problem in problems:
        print(f'::warning::Release note: {problem}', file=sys.stderr)

    print(json.dumps(for_phones_installed(unique), indent=2, ensure_ascii=False))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
