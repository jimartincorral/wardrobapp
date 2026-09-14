# Working in this repository

## Every change needs a release note

This app is not on an app store. It updates itself, and the dialog that asks
somebody to install a build shows a changelog — which is built from
`Release-Note:` trailers and from nothing else. A change without one does not
appear in it.

Put the trailer in the block at the end of the commit message, beside
`Co-Authored-By`, which is where git looks for trailers and the only place they
count:

```
Release-Note: An outfit keeps its rating when you edit it.
```

Write it for the person deciding whether to spend a download on this build, not
for the person about to read the diff. Those are different sentences: "Make a
duplicate a rule instead of a score" is a good pull request title and was a
useless changelog line. If the change is internal — CI, refactors, tests, build
configuration, anything nobody using the app could notice — say that instead:

```
Release-Note: none
```

Several trailers become several lines, and they may be on any commit the merge
brings in, so the line can be written while the work is fresh. A change carrying
neither is named in a warning on the release run. See `scripts/release-notes.py`,
which explains the rest, and the README section it points at.

## Things that are easy to get wrong here

- **`:app` does not build without the Android SDK.** `settings.gradle.kts`
  includes it only when one is present, so on a machine without it
  `./gradlew test` runs the three pure-Kotlin modules and silently skips the app.
  CI is the only place `:app` is compiled, linted or Robolectric-tested — which
  is why `:presentation` carries tests that read `:app`'s resources as files.
  Run `./gradlew test` before pushing; expect CI to be the first thing that
  compiles the app itself.

- **A `--` inside an XML comment is not a comment.** XML forbids the sequence,
  and the manifest merger's answer is `Error parsing AndroidManifest.xml` with no
  line and no column. This codebase uses dashes for asides constantly, so it is
  easy to write by accident; use em dashes in XML and `--` only in Kotlin and
  Python. `XmlWellFormedTest` exists because this has happened more than once.

- **Lint runs with warnings as errors, and there is no baseline.** An unused
  resource, a missing `contentDescription`, an unsafe call — each one fails the
  build rather than being recorded. Remove resources you stop referencing.

- **User-facing strings live in `res/values/strings.xml`,** with the same names in
  `values-es/`. `HardcodedStringTest` and `StringResourceParityTest` enforce both
  halves, in `:presentation`, because `:app` cannot be linted without the SDK.

- **The launcher icons are generated, not drawn.** They are cut from
  `art/logo.png` by `scripts/generate-launcher-icons.py`; run it after changing
  the logo rather than editing any PNG under `res/mipmap-*` or the brand mark
  under `res/drawable-*`.

## House style

The comments in this repository explain *why*, at length, and say what was tried
and rejected. Match that when you touch a file — a change that arrives with
terse comments in a codebase full of reasoning reads as unfinished. The same goes
for commit messages: they are written to be read later by somebody wondering what
this was for.
