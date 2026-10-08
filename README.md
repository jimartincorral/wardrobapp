# Wardrobapp

**Your wardrobe, on your phone.** Photograph the clothes you own and Wardrobapp
keeps a catalogue of them. It suggests outfits from what is actually in your
wardrobe, and learns your taste from how you rate them.

There is no account to create and no subscription. Nothing about you goes to a
server: your wardrobe stays on your phone, or in your own Home Assistant if you
use it there.

Wardrobapp comes as an **Android app**, and as a **Home Assistant app** that
opens in any browser signed in to your Home Assistant. The two can keep one
wardrobe in step. It is available in **English and Spanish**.

> Wardrobapp changes often: every build of `main` is published, and the app
> offers it with its changelog. Design notes and what is planned are in
> [TODO.md](TODO.md).

## What it does

**Catalogue your clothes.** Each garment is a photo, plus a category, type,
colours, brand, size and tags. The colours are filled in from the photo for
you. If you have a lot to add, choose **Add several at once**: pick a batch of
photos, then give each one a category.

**Clean photos.** **Remove background** cuts the garment out of its photo, so
your wardrobe looks like a catalogue and not a pile of bedroom floors. This
runs on your phone and never uploads anything.

**Add from a shop's website.** Share a product page to Wardrobapp from your
browser, or paste its link, and the photos, name and brand are filled in from
the page.

**Get outfit ideas.** **Suggest outfits** builds outfits from your whole
wardrobe, or around one garment you are not sure how to wear ("what goes with
this?"). It takes colours, seasons and occasions into account, and each idea
says why it was suggested.

**Teach it your taste.** Rate the ideas, and the app learns which garments go
together for you, which ones you never reach for, and which colour combinations
you really wear. You don't have to set anything up: suggestions get better the
more you rate. Rating an idea doesn't save it as an outfit. You choose whether
to keep it.

**Make your own outfits.** Put one together by hand on the **Outfits** tab, or
change one you have saved.

**Avoid buying the same thing twice.** When you add a garment, the app tells you
if it looks like something you already own.

**See what you own.** **Statistics** shows how many garments you have, broken
down by category, colour and brand. It also shows how long the things you
stopped wearing lasted, and which garments would complete outfits you can't put
together yet. Tap any number to see the garments behind it.

**Find things quickly.** Filter the wardrobe by any brand, size or colour you
own. View it as a list, or as a grid of two, three or four photos across.

**Retire clothes without losing them.** Mark a garment **No longer wearing
this** and it stops appearing in the wardrobe and in outfit ideas, but stays in
your history and statistics.

## Getting the Android app

Wardrobapp is not in the Play Store. You install it from this project's
releases page:

1. On your phone, download
   [**wardrobapp.apk**](https://github.com/jimartincorral/wardrobapp/releases/download/nightly/wardrobapp.apk)
   from the [latest release](https://github.com/jimartincorral/wardrobapp/releases/tag/nightly).
2. Open the downloaded file. Android will ask whether to allow installs from
   your browser or file manager: allow it, then tap **Install**.
3. Open **Wardrobapp**. A few welcome screens explain the basics. You can skip
   them, and they are also where you restore a backup if you are moving from
   another phone.

You need Android 7.0 or newer.

### Updates

The app checks for a newer version each time you open it. If there is one, it
lists what has changed and offers **Install**, **Skip this build** or **Later**.
Updating keeps your wardrobe. The first time, Android asks you to allow
Wardrobapp to install apps. This lets it *offer* the update: Android still asks
you before anything is installed.

After an update, **What's new** lists the changes. Some of them have a
**Show me** button that takes you straight to them.

### Checking a download is genuine (optional)

Every official build is signed with the same key. To check an APK you were sent,
run `apksigner verify --print-certs wardrobapp.apk` on a computer. Its SHA-256
fingerprint should be:

```
f35c02f1d70160524b637859898085c852ca98180a14852d0137eba6b1738197
```

> **Installed before 28 August 2026?** Builds from before that date were signed
> with a different key, and Android won't update one to the other in place. If
> an update fails to install, make a backup, uninstall the app, install the new
> version and restore the backup. You only have to do this once.

## Keeping your wardrobe safe

Your wardrobe is stored only on your phone, and uninstalling the app deletes
it. Back it up.

- **Create a backup** (in **Settings**) saves the whole wardrobe, photos and
  all, as one `.zip` file wherever you choose. **Restore from a backup** brings
  it back, on this phone or a new one. The backup is checked before anything is
  replaced, so a damaged file can't wipe your wardrobe.
- **Google Drive.** Connect your Drive under **Settings → Google Drive**, and
  the app can back up automatically: daily, weekly or monthly, keeping as many
  past backups as you choose. You can also limit it to Wi-Fi, and to when your
  battery isn't low. The app can only see the files it creates in your Drive,
  nothing else.
- **Optimize storage** frees space by shrinking oversized photos and deleting
  photos that no garment uses any more.

## Home Assistant

If you run [Home Assistant](https://www.home-assistant.io/), you can use
Wardrobapp in it too. It opens from the sidebar on any phone, tablet or
computer signed in to Home Assistant, and on a large screen it uses the extra
space.

**To install it:**

1. In Home Assistant, go to **Settings → Add-ons** (called **Apps** in newer
   versions) and open the store.
2. Choose **⋮ → Repositories** and add
   `https://github.com/jimartincorral/wardrobapp`.
3. Install **Wardrobapp**, start it, and open **Wardrobe** in the sidebar.

You need Home Assistant 2024.1 or newer, on a 64-bit Intel/AMD or ARM machine
(such as a Raspberry Pi 4 or 5).

**Everyone at home can have their own wardrobe.** The first time someone opens
it, they choose their own wardrobe or start a new one, and after that Home
Assistant opens theirs for them. Separate wardrobes keep everyone's clothes
apart, but they are not private: anybody who can open the app can switch
between them.

**Your wardrobe is part of Home Assistant's backups.** It is stored in Home
Assistant, so backing up Home Assistant backs up your wardrobe.

**A few things only the phone can do.** Cropping photos, backups to a file or to
Drive, and updates belong to the Android app. Removing backgrounds works in the
browser too: your Home Assistant machine does it, which takes a few seconds on a
Raspberry Pi.

### Syncing your phone with Home Assistant

The Android app works fine on its own. If you also use Home Assistant, the two
can share one wardrobe:

1. In the Wardrobapp add-on's **Configuration** tab, under **Network**, give
   port 8100 a host port (8100 is fine).
2. Open **Wardrobe** in the sidebar, go to **Settings**, and find the QR code
   under **Phone sync**.
3. On your phone, go to **Settings → Home Assistant**, tap **Scan pairing
   code**, point it at the QR code and tap **Connect**. You can also point
   your phone's camera app at it. If your phone can't scan, type the address
   and code shown under the QR code. On a phone you have just installed the
   app on, the welcome screen offers **Pair with Home Assistant**, which is
   the same scan: the wardrobe arrives with the first sync.

After that the phone syncs when you open the app, when you tap **Sync now**,
and every few hours in the background. If a garment was changed in both places,
the most recent change wins. Something deleted in one place is deleted in both.

Some things to know:

- **Restoring a backup on a synced phone replaces the wardrobe everywhere,**
  including Home Assistant and every other synced phone. To restore on one
  phone only, tap **Stop syncing** on it first.
- **Lost a phone?** **Make a new code** in the browser's Settings disconnects
  every phone using the old one.
- Sync is meant for your home network. If you make the port reachable from
  outside your home, put HTTPS in front of it.

The add-on's [own documentation](homeassistant/wardrobapp/DOCS.md) goes into
more detail.

## Privacy

Wardrobapp has no analytics, no ads, no tracking and no account. The developer
runs no server, so there is nowhere for your data to be sent. The app goes
online only for things you ask it to do, and to check for an update when you
open it.
[PRIVACY.md](PRIVACY.md) lists every one of them.

## Good to know

- **No iPhone version.** Wardrobapp is for Android, and for browsers through
  Home Assistant.
- **It records ratings, not what you wore.** There is no wear diary, so it
  can't tell you cost per wear.
- **Colour detection is a best guess.** It picks a garment's main colour, and a
  second one if there is a lot of it. Patterns, and photos whose background
  hasn't been removed, can confuse it. Undoing a colour it picked takes one tap.
- **Imports only fetch public websites.** The app asks before it fetches a link,
  and refuses addresses on your home network. Shops that still use plain
  `http://` addresses can't be imported from.
- **Old backups are managed in your Files app.** The app doesn't list or delete
  the backups you have saved; delete old ones in your Files app.
- **Sync is with Home Assistant only.** No cloud sync service is involved. To
  move your wardrobe to a new phone without Home Assistant, use a backup.

## Help and feedback

Found a bug or have an idea? [Open an issue](https://github.com/jimartincorral/wardrobapp/issues).

Want to build the app yourself or contribute? Start with
[CONTRIBUTING.md](CONTRIBUTING.md).

## License

Copyright © 2026 the Wardrobapp authors; the git history names them.

Wardrobapp is free software under the [AGPL-3.0](LICENSE). You can use, change
and share it freely, including running it as a service, as long as you publish
the full source of your changed version under the same license. For
closed-source or commercial use, contact the author about a commercial license.

It builds on other people's work, which [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md)
credits: the icons, the background-removal model, and the QR code algorithm
among them.
