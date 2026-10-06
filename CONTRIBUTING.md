# Contributing to Wardrobapp

This is the developer's half of the documentation: how the app is built,
tested, signed and released, and why it is shaped the way it is. The
[README](README.md) is for the people who use it, and says nothing in here.

This is a personal project, but issues and PRs are welcome. For anything non-trivial, open an issue first so we can talk through scope. Pull requests must pass `./gradlew test` and the Android job, and every change needs a `Release-Note:` trailer — see [Writing the changelog](#writing-the-changelog).

By contributing, you agree that your contributions are licensed under the project's AGPL-3.0 license.

## Tech stack

- **UI:** Compose Multiplatform 1.7 — Jetpack Compose itself on Android — Material 3, single activity, Navigation Compose
- **Language:** Kotlin 2.1, JVM target 17, `minSdk` 24 / `targetSdk` 36
- **Storage:** SQLite through a small `SqlDriver` interface — `SupportSQLiteDatabase` on a device, JDBC in tests and on the Home Assistant server; photos as files under `<documents>/garment-images/`, referenced from the database by filename
- **Build:** Gradle with AGP 8.9, nine modules; the only generated code is Compose Multiplatform's accessors for the screens' resources
- **Dependencies:** AndroidX, ML Kit, Coil 3 for loading photos, and the same crop screen Expo's image picker used. No dependency injection framework, no ORM.

## Building

### Prerequisites

- JDK 17
- The Android SDK, with platform 36 (Android Studio, or `sdkmanager`)

Everything but the app needs neither — see [Architecture](#architecture).

```bash
git clone https://github.com/jimartincorral/wardrobapp.git
cd wardrobapp

# The pure modules: the algorithms, the data mapping, the view logic, and
# URL import's requests -- and every screen, compiled for the browser.
# No Android SDK needed, and finishes in seconds.
./gradlew test

# One class. In the multiplatform modules the JVM tests are `jvmTest`, which
# is the task that takes --tests; `test` runs them and compiles for the browser.
./gradlew :domain:jvmTest --tests '*UrlSafetyTest*'

# The app, on a connected device or emulator.
./gradlew installDebug
```

A debug build installs as `com.anonymous.wardrobapp.debug`, alongside the real app rather than over it. The first launch creates the SQLite schema.

### Build a release APK

```bash
./gradlew assembleRelease
# app/build/outputs/apk/release/app-release.apk
```

CI builds one on every push and publishes it from `main` to the rolling [`nightly` release](https://github.com/jimartincorral/wardrobapp/releases/tag/nightly), which installs over whatever version is on the phone and keeps the wardrobe.

`versionCode` is a fixed offset plus the CI run number, so each published build is a later version than the last. Locally it is the offset alone, which is fine: a local build is not upgrading anything.

## Testing

```bash
./gradlew test                    # 959 tests, no Android SDK, seconds
./gradlew :app:testDebugUnitTest  # 192 more, needs the SDK — no emulator
```

The 959 cover the suggestion engine, duplicate detection, colour comparison, pair learning, URL safety and which addresses will be fetched, reading a product page, row normalization against every list-column shape that exists, the two database schemas in the wild, backup validation and its refusal messages, which published build is worth offering and where an update may be downloaded from, the form rules, filtering and ordering, the chart arithmetic, each screen's logic against a fake source, every type a source hands over going to JSON and back, the server's routes driven by the browser's own sources, and both languages' string resources against each other.

The 192 in `:app` are Robolectric tests, not instrumented ones — what a screen shows, where a file lands, and what another activity is asked for, which is the part no pure module can answer:

```bash
./gradlew :app:testDebugUnitTest
```

The debug variant by name, not `:app:test`: `ui-test-manifest` supplies the activity the Compose tests compose into, and it is a debug-only artifact by design, so running them against release fails every one of them.

Lint runs every check it has, with warnings failing the build and no baseline file — the backlog was cleared rather than frozen. The two version-nag checks are informational, since a new AndroidX release is not a defect in any commit.

Algorithms are checked by mutation: each behaviour the tests claim to protect is removed in turn, and the intended test must fail. A test that passes without the code it covers is not a test.

CI runs all of it on every pull request, on pushes to `main`, and on pushes to `claude/**` branches — the Android job is the only place `:app` compiles or lints at all, so a branch needs to be able to run it without opening a pull request first.

## Architecture

One module that builds only where there is an Android SDK, `:app`; six that need nothing but a JDK — `:domain`, `:data`, `:presentation` and `:api`, which are Kotlin Multiplatform, and `:net` and `:server`, which are plain Kotlin/JVM; `:ui`, the screens, which builds for Android, the desktop JVM and the browser where there is an SDK and for the browser alone where there is not; and `:web`, the browser app, which is Wasm alone. `settings.gradle.kts` includes `:app` only when an SDK is present, and picks `:ui`'s browser-only build file when one is not, which is what lets everything but the app be built and checked on any machine — and proves it needs nothing but a JDK, rather than merely claiming it.

- **`domain/`** — no database, no filesystem, no clock, no Android. Everything arrives as an argument: the suggestion engine takes its randomness as a parameter, so a run is reproducible and a bug can be reported. Common code that also compiles for the browser, apart from URL import, which only runs where a page is fetched and stays on the JVM. `./gradlew test` compiles the Wasm target as well as running the tests, so code that only builds on the JVM fails there rather than in the browser build.
- **`presentation/`** — the decisions a screen makes, taken out of the screen. Chart widths, what counts as an active filter, which photo the strip has selected. Compose renders the answers; it does not compute them. Common code, so the browser's screens make the same decisions; sorting by name uses each platform's own collator.
- **`data/`** — reaches SQLite through a small `SqlDriver` interface rather than depending on `androidx.sqlite`. On Android that wraps a `SupportSQLiteDatabase`; in tests and on the Home Assistant server it wraps JDBC, through `JdbcSqlDriver`, which compiles against `java.sql` alone so the phone never carries a JDBC driver. Both run the same SQL against the same schema, which is what lets the queries be exercised without an emulator. The records, queries, writes and schema are common code; the backup archive, file locations, Drive requests and write timestamps stay on the JVM, where the phone and the Home Assistant server run them.
- **`net/`** — the one pure module that does I/O: the requests URL import makes. It holds no decisions — whether an address may be fetched is `:domain`'s — and it is separate from `:app` so that what a request actually reaches can be tested against a real server without an SDK.
- **`api/`** — the wardrobe over HTTP, both halves of it: the routes, the way a failure travels so a screen can still tell an unsafe address from a page with no garment on it, and an implementation of every screen's source that asks the server instead of a database. The browser's screens are handed these; `:server`'s tests drive them against a real server, so the client and the server are tested against each other. Also the phone's half of syncing with Home Assistant — `PhoneSync`, which decides when a sync runs and what Settings is told, and the client it runs — kept here rather than in `:app` so it is tested against a real server the same way.
- **`server/`** — what the Home Assistant app runs: a Ktor server holding a wardrobe in the same SQLite schema and photo layout as the phone, answering `:api`'s routes with the same Database sources the phone's screens use. It decides nothing about a wardrobe itself, which is why a browser asking it gets the answer the phone would have given. It checks every uploaded photo by its bytes and every photo name it is asked for, and trusts Home Assistant's ingress to say who is asking.
- **`ui/`** — the screens, in Compose Multiplatform: every layout, the strings in both languages, the glyphs and the brand mark. Common code, for the phone and the browser alike; where the platforms genuinely differ — Android 12's dynamic colour, how tall the window is — it is an `expect` with each platform's answer. Its Android and desktop builds need the SDK, because the Android library plugin and the androidx artifacts behind Compose Multiplatform's Android and desktop builds are on Google's Maven; its browser build needs only Maven Central, so `./gradlew test` compiles every screen for the browser everywhere. The two build files that makes are held equal by `UiBuildFilesTest`. The Robolectric tests in `:app` exercise these screens, and `:app`'s lint checks them.
- **`web/`** — the browser app the Home Assistant server hands out: the browser's `MainActivity`, and nothing more. A back stack shaped like the phone's, kept in step with the browser's own back button; each screen given its model from `:presentation` and its source from `:api`; preferences in the browser's local storage; and photos picked, scaled and encoded by the browser to the size and quality the phone stores, then uploaded. What the phone does with Android alone — the crop screen, Drive, backups, updates — is left off the screens here rather than offered and failed. Removing a background is asked of the server, and offered only when the server says it has the model to. On a window 1200dp wide or more — a desktop browser, with Home Assistant's sidebar taken off — the same screens are laid out for the room: a navigation rail instead of the bottom bar, and panes side by side where the phone stacks them, so the wardrobe's filters, its grid and the garment open in it are on screen together. Where the line falls is `WindowWidth` in `:presentation`; `:ui` reads it from a composition local that only the browser sets, so the phone draws exactly what it always has.
- **`app/`** — navigation and the platform: `MainActivity`, the ViewModels that fill each screen's state, the photo pipeline, Drive, and updates. Thin on purpose: a ViewModel here loads data, calls a pure function and holds the result.

`WardrobeSchema` is applied on every open — `CREATE TABLE IF NOT EXISTS`, then additive `ALTER`s, then the indexes over them — so there is no migration version to get out of step. Two shapes of database exist on real phones as a result, and both are tested; see Limitations.

### Project structure


```
app/           The Android app: Compose screens, ViewModels, and the platform
               plumbing that genuinely needs Android — the camera, the document
               picker, SQLite, ML Kit, the share target. 30 files, and the only
               module that needs the SDK.
presentation/  What a screen shows, as pure functions over records: list
               filtering and ordering, form state, the detail view, the chart
               arithmetic, the colour a photo suggests.
domain/        The algorithms: outfit suggestion, duplicate detection, pair
               learning, colour comparison, occasions, URL safety, reading a
               product page.
net/           URL import's requests: a product page and its images, with
               every redirect checked before it is followed.
data/          SQLite queries and row mapping, photo references, reading and
               writing backup archives.
ui/            The screens, in Compose Multiplatform, for the phone and the
               browser alike.
api/           The wardrobe over HTTP: routes, and every screen's source as
               requests to the server.
server/        The Home Assistant app's server, answering those requests.
web/           The browser app the server hands out.
homeassistant/ The Home Assistant app: its config.yaml, Dockerfile and docs.
art/           logo.png — the logo, as delivered. Every launcher icon the app
               ships is cut from this file.
               glyphs/ — the Material glyphs the app vendors, as SVG, each
               naming the upstream file it came from.
scripts/       generate-launcher-icons.py — cuts them, with no dependencies.
               generate-glyphs.py — turns the glyphs into Compose vectors.
               release-notes.py — the changelog the update dialog and What's
               new show; test_release_notes.py tests it.
```

### Before this was a Kotlin app

Until [`0ca397a`](https://github.com/jimartincorral/wardrobapp/commit/0ca397a) this repository held a React Native app, and this one is a port of it rather than a rewrite: the same schema, the same photo layout, the same archive format, the same algorithms down to the arithmetic. That app is deleted, and its last version is one commit away if it is ever needed.

Two things it left behind, both deliberate. Comments across this codebase explain a decision by referring to "the React Native app" — that is the app above, and those explanations are still why the code looks as it does: the database lives in `files/SQLite/` because that app put it there, and it is still there on every phone that has ever run this one. And the port was verified against it by recording 3341 of its answers and replaying them in Kotlin; that corpus went when its oracle did, replaced by tests that state what has to be true rather than that two implementations agree.

### Known quirks

**`garments` is not one schema.** `created_at` and `updated_at` are `NOT NULL` on a fresh install and nullable on one upgraded through the `ALTER` path, because SQLite cannot add a `NOT NULL` column without a default. Both populations exist on phones, so readers tolerate both — and it is why this layer uses plain SQL rather than Room, whose schema validation would reject one of them.

## Releasing

### Writing the changelog

The update dialog's list is not the pull request titles. It is written by hand, one
line at a time, as a trailer on the commit that does the work:

```
Release-Note: An outfit you have rated keeps its rating when you edit it.
```

A pull request title names the change, for somebody about to read the diff. A
changelog line names what is different, for somebody deciding whether to spend a
download on it. They are different sentences, and a change that has only the first
one says so:

```
Release-Note: none
```

It belongs in the trailer block at the end of the message, beside `Co-Authored-By`
and the rest — git's own definition of a trailer, which is what stops a commit that
merely discusses the convention from being read as carrying one.

Several trailers in one branch become several lines; the trailer may be on any
commit the merge brings in, so it can be written when the work is done rather than
remembered at merge time. A change that carries neither contributes nothing and is
named in a warning on the release run — silence and "nothing to say" look the same
in a changelog, and only one of them is deliberate.

A note can also say what kind of change it is, which app it is about and where
it can be seen, and carry its Spanish:

```
Release-Note: [new android -> settings] Your wardrobe can sync with Home Assistant.
Release-Note-es: Tu armario puede sincronizarse con Home Assistant.
```

- **Kind:** `new`, `improved` or `fixed`.
- **App:** `android` or `web`, the browser in Home Assistant.
- **Destination:** one of the screens listed in `release-notes.py`, which
  `ReleaseNoteDestinationsTest` holds equal to the app's.

Every part is optional. A note with no brackets is an improvement to both apps,
which is how every note written before the brackets reads. The Spanish pairs with
the English by position within the commit. A note without Spanish shows its
English to somebody reading in Spanish. A word in the brackets that the script
does not know costs a warning on the release run, not the note.

Every note is published with the build it arrived in, and the last fifty travel
forward from one release to the next. So the update dialog lists everything since
the build on the phone rather than only what the newest build added, and a phone
that missed a few launches is told about all of them. The dialog shows only notes
about the phone, in the app's language.

Once the update is installed, the new build shows **What's new**: the same notes
since the last build it was shown for, under New, Improved and Fixed. A note with
a destination gets a **Show me** button, which only the new build can offer.
Nothing is shown on a fresh install.

The browser has What's new too, after Home Assistant updates the app. Its notes
are the ones tagged `web`, or not tagged at all. Each note belongs to the version
whose bump first shipped it: the release workflow walks every merge since the
app existed, reads `config.yaml` at each, and writes the result into the image
(`release-notes.py --web-history`). The browser remembers the last version it
showed notes for. A browser that has never shown them shows nothing, since it
cannot tell a new visitor from somebody who used a version from before this
existed.

The Home Assistant app's `CHANGELOG.md` is written by hand when its version is
bumped. `python3 scripts/release-notes.py <last-bump> HEAD --home-assistant`
prints the browser's notes since then as a first draft. The script's own tests
are `python3 -m unittest discover -s scripts`.

### The Home Assistant app

How to install it, and how profiles and syncing look to somebody using them, are
in the [README](README.md#home-assistant) and in the app's own
[`DOCS.md`](homeassistant/wardrobapp/DOCS.md). This is how it works underneath.

It is one container: `:server`, which holds the wardrobe in the same SQLite
schema and photo layout as the phone and answers with the same code, and
`:web`, the browser app it serves. Home Assistant's ingress is the only way in
for a browser, and the server refuses any request on that port that does not
come from ingress's address.

The server removes backgrounds for the browser, which has nothing to do it
with. It runs silueta, a 44 MB U²-Net, through ONNX Runtime's Java binding,
which carries native code for both of the image's architectures; Gradle
downloads the model when it builds the server and checks it against a pinned
digest, so it is not in the repository. The model is loaded when a photo is
first cut out and closed two minutes after the last, so the add-on does not
hold the memory between uses. `BackgroundRemover` explains why this model and
not the others tried.

It holds a wardrobe per person: named profiles, each with its own database,
photos and pairing code, in its own directory under `/data` (`ProfileRegistry`).
Each Home Assistant user opens their own, which ingress tells the server by the
`X-Remote-User-Id` header it sets. Anyone can switch to another, so somebody
without a Home Assistant login can still have one. A profile's routes are the
same routes under `p/<id>/`, and its photos are referenced the same way. The
wardrobe there was before profiles became the first, in place, under the id
`main`. Any profile but the last can be deleted, which takes its files with it:
its whole directory, or for `main`, whose directory is `/data` itself, only the
wardrobe's own files there.

The Android app can sync with it. That goes through a second port, 8100, which
answers nothing but sync and only to a phone carrying the pairing code shown in
the browser's Settings; Home Assistant keeps it closed until it is given a host
port in the app's **Network** settings. A phone syncs with the profile whose
code it holds. Each sync exchanges the whole wardrobe
and keeps the latest change to each garment and outfit, a deletion included;
photos move by name, only when one side lacks them. The phone syncs when it is
opened, from **Sync now**, and every few hours in the background, on Wi-Fi only
unless told otherwise — and keeps working on its own whether or not it is
paired. Restoring a backup on a paired phone replaces the wardrobe everywhere
rather than merging: the phone stamps what it restored as changed now and sends
it to `sync/v1/replace`, which deletes what the backup does not have
(`SyncStore.replaceWith`). Without that, the next sync would merge the backup
with the wardrobe it was meant to replace, and every edit since the backup would
win. The merge is `WardrobeSync.kt` in `:data`, run by the phone and the
server alike; the app's `DOCS.md` says how to pair.

The images are built by `.github/workflows/home-assistant.yml` on every pull
request, and published to GitHub's container registry from `main` when
`homeassistant/wardrobapp/config.yaml` names a version that is not published
yet. **Bumping that version, with an entry in the app's `CHANGELOG.md`, is what
releases it**; a version is never republished. The entry's first draft is the
browser's release notes since the last bump — see [Writing the
changelog](#writing-the-changelog). `HomeAssistantAppTest` holds the
config, the Dockerfile, the workflow and the server's defaults to each other.

To run the same thing locally:

```bash
./gradlew :server:installDist :web:wasmJsBrowserDevelopmentExecutableDistribution
WARDROBAPP_DATA=/tmp/wardrobe \
WARDROBAPP_WEB=web/build/dist/wasmJs/developmentExecutable \
  server/build/install/wardrobapp-server/bin/wardrobapp-server
# then open http://localhost:8099/
```

The development bundle builds in about a minute; `wasmJsBrowserDistribution`,
the optimised one the image ships, takes several.

### Signing

Published builds are signed with a **private release key**, held by the maintainer and
given to CI as four repository secrets. Its certificate is

```
CN=jimartincorral, C=ES
SHA-256  f35c02f1d70160524b637859898085c852ca98180a14852d0137eba6b1738197
SHA-1    c9c04a682b973e52b93edc82d5a39facfea438bf
```

so a build claiming to be an update of this app can now be checked against something,
rather than taken on trust:

```bash
apksigner verify --print-certs wardrobapp.apk
```

`keytool` prints the same fingerprint colon-separated and uppercase; it is the same
number. CI asserts it on every release: with a keystore configured the signing check
inverts, and an APK still carrying the public debug key fails the build.

Every build published before 28 August 2026 — including all of them from before this
was a Kotlin app — carried the **public Android debug key** from Expo’s project
template instead. `app/debug.keystore` is that key, still committed, and still the
fallback when no keystore is configured: a fork or a contributor without the secrets
must be able to build, and `app/build.gradle.kts` explains at length beside the config
why it is in the repository at all. It signs nothing published from here any more.

Moving between the two was a one-time break, and it is done. Android replaces an
installed app only with a build signed by the same key, so the first signed build could
not upgrade anything: it needed a backup, an uninstall, an install and a restore, once
per device. Every build since upgrades in place as before.

**Setting this up on a fork.** Create a keystore — needs a desktop, and both passwords
want keeping somewhere you will still have them in five years, because losing them
means never being able to upgrade an installed app again:

```bash
keytool -genkeypair -v -keystore wardrobapp-release.keystore -alias wardrobapp -keyalg RSA -keysize 2048 -validity 10000
```

Then add four repository secrets (Settings → Secrets and variables → Actions):

| Secret | Value |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | the keystore, base64-encoded (`base64 -w0 wardrobapp-release.keystore`) |
| `ANDROID_KEYSTORE_PASSWORD` | the keystore password |
| `ANDROID_KEY_ALIAS` | the key alias (`wardrobapp` above) |
| `ANDROID_KEY_PASSWORD` | the key password |

CI decodes the keystore and passes the rest to Gradle as
`ORG_GRADLE_PROJECT_WARDROBAPP_*` properties. The same four work locally, either as
`-PWARDROBAPP_STORE_FILE=…` or in `GRADLE_USER_HOME/gradle.properties`, which keeps the
passwords out of your shell history and out of the repository. Use forward slashes in
that path: a `.properties` file reads a backslash as an escape.

### Google Drive sign-in

Backing up to Drive needs an OAuth client, and there are three settings that are
easy to get wrong and give errors that do not name themselves. All three are in the
Google Cloud console under **APIs & Services → Credentials**; none of them are in
this repository.

**One client per build type, because Google keys an Android client on the
application id *and* the signing certificate.** A debug build differs in both, so
one client cannot cover both. The ids are committed in `app/build.gradle.kts`
rather than kept as secrets: an Android client has no secret, the id ships inside
the APK, and what stops somebody else using it is the pair Google checks it
against.

| | application id | certificate |
|---|---|---|
| release | `com.anonymous.wardrobapp` | the release key, SHA-1 `c9c04a682b973e52b93edc82d5a39facfea438bf` |
| debug | `com.anonymous.wardrobapp.debug` | the committed debug key |

**Custom URI schemes must be switched on, per client.** Google disables them by
default on Android clients created since 2022. Without it the browser opens, the
sign-in page loads, and Google refuses with *"Custom URI scheme is not enabled for
your Android client"* — which reads like a bug in the app and is not. The toggle is
under **Advanced settings** on the client itself, and takes a few minutes to take
effect.

**A new keystore means updating the release client's fingerprint.** Sign-in then
breaks in release only, while debug keeps working and hides it.

**The automatic backup is configurable, and its settings are this phone's.** How
often (daily, weekly, monthly), how many archives the folder keeps (1, 3, 5, 10 or
all), and whether to wait for Wi-Fi and for charge, all live in SharedPreferences
rather than in the wardrobe -- so a restore from another device does not bring
somebody else's data-plan decisions with it.

**The consent screen has to be published, not left in testing.** A project in
Testing admits only the accounts on its test-user list, and refuses the rest with
*"Error 403: access_denied"* after the sign-in page has already loaded. Adding
yourself as a test user clears that, and should not be the fix: Google expires a
test user's refresh token after **seven days**, so everything works and then stops
a week later for no visible reason -- and an unattended backup is exactly the case
where nobody is watching to sign in again. Publishing avoids that, and needs no
verification review, which is one of the reasons `drive.file` was chosen over
`appDataFolder`: Google classes it non-sensitive.

Publishing does require four things filled in on the Branding page: an app name, a
support email, a homepage and a privacy policy. The last two are in this
repository -- the repository itself is the homepage, and [PRIVACY.md](PRIVACY.md)
is the policy, which GitHub serves at a URL Google accepts. It is written from what
the code does rather than from a template, so it names all four occasions on which
this app touches the network; if that ever stops being true, it is the file to
change.

The redirect URI is not registered anywhere: Google derives it from the package
name, and the app builds the matching one from `BuildConfig.APPLICATION_ID`
(`com.anonymous.wardrobapp:/oauth2redirect`). `appAuthRedirectScheme` in
`app/build.gradle.kts` has to match that per build type, or the browser will not
find its way back.
