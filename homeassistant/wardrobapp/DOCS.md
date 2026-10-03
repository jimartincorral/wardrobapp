# Wardrobapp

Wardrobapp keeps a catalogue of the clothes you own, suggests outfits from them
and learns from how you rate the suggestions. This app runs it inside Home
Assistant, so it opens from the sidebar on any phone, tablet or computer signed
in to Home Assistant.

## Using it

Start the app and open **Wardrobe** in the sidebar. Everything works as it does
in the Android app, with a few things that need the phone:

- **Removing a photo's background** and **cropping** are not available in the
  browser. A cut-out made on the phone can still be undone.
- **Photos** are scaled to the size the phone stores before they are uploaded,
  so a large photo from a camera takes a moment.
- **Language** follows your browser's.

## Syncing the Android app

The Android app keeps working on its own, and can also keep its wardrobe in
step with this one:

1. In this app's **Configuration** tab, under **Network**, give the phone sync
   port (8100) a host port — 8100 is fine unless something else uses it.
2. Open **Wardrobe** in the sidebar, go to **Settings**, and find the pairing
   code under **Phone sync**.
3. In the phone's **Settings → Home Assistant**, enter this Home Assistant's
   address with that port (for example `http://homeassistant.local:8100`) and
   the code.

The phone then syncs when it is opened, from **Sync now**, and in the
background if you let it. When the same garment or outfit was changed in both
places, the latest change wins; something deleted in one place is deleted in
both.

The sync port answers nothing but sync, and only to a phone that has the code.
**Make a new code** in Settings unpairs every phone at once — the way to shut
out a phone that was lost. The code travels over your home network as plain
HTTP; if the port is reachable from outside your home, put it behind HTTPS.

## Your data

The wardrobe — its database and its photos — is kept in this app's own storage,
which Home Assistant includes in its backups. Backing up Home Assistant backs
up your wardrobe; nothing is sent anywhere else.

The app answers only through Home Assistant: it publishes no port, and it
refuses any request that did not come through Home Assistant's own sign-in.

## Importing from a link

Pasting a product page's address into the garment form fetches the page and
its photos from Home Assistant's machine. Addresses on your own network are
refused, for the same reasons the Android app refuses them.
