#!/usr/bin/env python3
"""
What to tell somebody about a build, as opposed to what changed in it.

    python3 scripts/release-notes.py <previous-commit> <this-commit>

Prints a JSON array of lines for the update dialog, and says on stderr which
merges had nothing to contribute and did not say so.

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
import subprocess
import sys

KEY = 'Release-Note'

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


def notes_in(commits: list[str]) -> tuple[list[str], bool]:
    """
    Every release note across these commits, and whether any of them said anything.

    The second half of that is not just "the list is empty": `Release-Note: none`
    is a decision and an absent trailer is an oversight, and they have to be told
    apart in order to warn about one and not the other.

    Git does the parsing rather than a search for lines starting with the key,
    which is what this did first and which is wrong in a way that took a commit
    about trailers to notice: that commit quoted `Release-Note:` twice as an
    example, indented in its own prose, and both examples went into the changelog.
    A trailer is only a trailer in the block at the end of the message, and
    `%(trailers:key=...)` is git's own rule for what that means -- the same rule
    that decides whether Co-Authored-By counts.
    """
    notes, spoken = [], False
    for commit in commits:
        values = git(
            'log', '-1', f'--format=%(trailers:key={KEY},valueonly,unfold)', commit)
        for value in values.splitlines():
            text = value.strip()
            if not text:
                continue
            spoken = True
            if text.lower() not in ('none', 'n/a', 'no', '-'):
                notes.append(text)
    return notes, spoken


def main() -> int:
    if len(sys.argv) != 3:
        print(__doc__.strip().splitlines()[2].strip(), file=sys.stderr)
        return 2
    previous, head = sys.argv[1], sys.argv[2]

    changes: list[str] = []
    silent: list[str] = []

    # First parent, so this walks what landed on the branch -- one step per merge
    # -- rather than every commit inside each one.
    for commit in git('log', '--first-parent', '--format=%H', f'{previous}..{head}').split():
        parents = git('log', '-1', '--format=%P', commit).split()

        # A merge answers for itself and for everything it brought in; a commit
        # pushed straight to the branch answers only for itself.
        scope = [commit]
        if len(parents) > 1:
            scope += git('rev-list', f'{parents[0]}..{parents[1]}').split()

        notes, spoken = notes_in(scope)
        changes += notes
        if not spoken:
            subject = git('log', '-1', '--format=%s', commit).strip()
            body = git('log', '-1', '--format=%b', commit).strip().splitlines()
            silent.append(body[0] if body else subject)

    # Deduplicated in order: a trailer written on a branch and repeated in the
    # merge is one line, not two.
    seen = set()
    unique = [note for note in changes if not (note in seen or seen.add(note))]

    if silent:
        print(
            f'::warning::{len(silent)} change(s) in this build carry no Release-Note '
            'trailer, so they are not in the changelog. Add one, or "Release-Note: none" '
            'to say there is nothing to tell: ' + '; '.join(silent),
            file=sys.stderr,
        )

    print(json.dumps(unique[:LIMIT] if unique else [NOTHING], indent=2))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
