# Privacy Policy

**Wardrobapp**
Last updated: 29 August 2026

Wardrobapp is a local-first wardrobe and outfit planner. There is no account to
create, no server run by its developer, and nothing about you is collected,
transmitted to us, or sold. This document says where your data lives and every
occasion on which the app uses the network, because a policy that only says "we
respect your privacy" tells you nothing you can check.

The app is open source, and every claim below can be verified in the source:
<https://github.com/jimartincorral/wardrobapp>

## What the app stores, and where

Everything you put into Wardrobapp stays on your device:

- Your garments, outfits, ratings and preferences, in a SQLite database in the
  app's private storage.
- Your photos, as files in the app's own documents directory.
- Your settings, including your theme and language choice.

None of it is sent anywhere by default. Uninstalling the app removes all of it.

## What the app never does

- No analytics, telemetry, crash reporting or usage tracking.
- No advertising, and no advertising identifiers.
- No account, no sign-up, no profile.
- No server operated by the developer. There is nowhere for your wardrobe to be
  sent to, because no such place exists.

## When the app uses the network

Four occasions, and no others. Three of the four happen only because you asked
for them.

**Checking for a new version.** The app fetches a small file from its own GitHub
release page to see whether a newer build exists. GitHub can see that some device
asked for that file, including its IP address, as any web request reveals.

**Importing a garment from a web page.** If you paste or share a product link, the
app fetches that page directly from your device to read its title and picture. The
site you named can see that request, including your IP address. No intermediary is
involved: the request goes from your phone to that site.

**Backing up to Google Drive, if you connect it.** Optional, and off until you
press the button. See the section below.

**Background removal.** Cutting a garment out of its background runs on your
device, using a model that Google Play services provides. The photo itself is not
uploaded; Play services may fetch the model itself over the network, which is
Google's process rather than this app's.

## Google Drive

Connecting Google Drive is optional. The app never does it on its own.

If you connect it, the app asks Google for the **`drive.file`** permission, which
is the narrowest one that can do the job. It means:

- The app can only ever see and change **files it created itself**. The rest of
  your Drive is invisible to it, including files you made with anything else.
- Your backups are written to a folder named *Wardrobapp* in your own Drive, as
  ordinary `.zip` files. You can open, download, copy or delete them without this
  app being involved. That is deliberate: a backup you can only reach by
  installing the app that made it is a worse backup.
- The archive contains your wardrobe database and your photos — the same file the
  app writes when you back up to your own storage.

The permission is held on your device only. It is never sent to the developer, who
has no access to your Drive and no way to obtain it.

**Disconnecting** forgets the permission on your device. It does not reach into
your Google account to revoke it, and it does not delete anything: your backups
stay where they are, in your Drive, yours to keep or remove. You can also revoke
the app's access entirely at
<https://myaccount.google.com/permissions>.

## Deleting your data

- **On the device:** uninstall the app, or clear its data from Android's app
  settings.
- **In Google Drive:** delete the *Wardrobapp* folder yourself, the same way you
  would delete anything else in your Drive. The app does not need to be involved
  and cannot object.

## Children

Wardrobapp is not directed at children and collects nothing from anybody,
including them.

## Changes to this policy

Changes are made in the repository's history, which is public, so what changed and
when is a matter of record rather than of trust.

## Contact

Questions, or anything here that turns out to be inaccurate:
<https://github.com/jimartincorral/wardrobapp/issues>
