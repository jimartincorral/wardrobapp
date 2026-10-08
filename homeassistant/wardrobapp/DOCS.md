# Wardrobapp

Wardrobapp keeps a catalogue of the clothes you own, suggests outfits from them
and learns from how you rate the suggestions. This app runs it inside Home
Assistant, so it opens from the sidebar on any phone, tablet or computer signed
in to Home Assistant.

## Using it

Start the app and open **Wardrobe** in the sidebar. Everything works as it does
in the Android app, with a few things that need the phone:

- **Removing a photo's background** is done by this app rather than by your
  browser, so it works from any device. It takes a few seconds on a Raspberry
  Pi, less on a faster machine, and needs about 150 MB of memory while it
  works, which is given back a couple of minutes after the last photo. The
  result is close to the phone's but not identical: the two use different
  models.
- **Cropping** is not available in the browser. A cut-out made on the phone
  can still be undone.
- **Photos** are scaled to the size the phone stores before they are uploaded,
  so a large photo from a camera takes a moment.
- **Language** follows your browser's.

## A wardrobe for each person

Everyone at home can have a wardrobe of their own. The first time somebody
opens **Wardrobe** in the sidebar, it asks whose wardrobe this is: they choose
theirs, or start a new one. After that, Home Assistant opens their own for them.
Anybody can switch to another person's wardrobe, rename one, or start one under
**Settings → Wardrobe**, so a child without a Home Assistant login can still
have a wardrobe.

The wardrobe the app had before it had profiles is kept as it was, and listed as
**The original wardrobe** until somebody renames it. Phones already paired with
it carry on syncing with it.

Profiles keep each person's clothes apart in everyday use. They do not keep them
secret: anyone who can open the app can switch to any wardrobe.

**Settings → Wardrobe → Delete** deletes the wardrobe showing: its clothes,
outfits, photos and pairing code. It asks first, and is offered only while
there is another wardrobe to go to. Only a Home Assistant backup made before
can bring a deleted wardrobe back. A phone that synced with it stops syncing
and keeps its own copy; pair it with another wardrobe to carry on.

## Syncing the Android app

The Android app keeps working on its own, and can also keep its wardrobe in
step with this one:

1. In this app's **Configuration** tab, under **Network**, give the phone sync
   port (8100) a host port — 8100 is fine unless something else uses it.
2. Open **Wardrobe** in the sidebar and go to **Settings**. Under **Phone
   sync** is a QR code. Each wardrobe has its own code, and a phone syncs with
   the wardrobe whose code it was given, so open your own first.
3. On the phone, go to **Settings → Home Assistant**, tap **Scan pairing
   code** and point it at the QR code, then tap **Connect**. Pointing the
   phone's camera app at it works too.

The QR code holds the address you opened Home Assistant at, with the host port
from step 1, and the code. Both are written under it, to type into a phone that
can't scan. If Settings says phones can't reach the app yet, step 1 hasn't been
done. If you opened Home Assistant through Nabu Casa's remote address, there is
no QR code: that address only reaches Home Assistant itself, so open Home
Assistant at the address you use at home to get one.

The app asks Home Assistant which host port you gave it, which is why it asks
for access to Home Assistant's Supervisor API. It reads only this app's own
information.

The phone then syncs when it is opened, from **Sync now**, and in the
background if you let it. When the same garment or outfit was changed in both
places, the latest change wins; something deleted in one place is deleted in
both.

Restoring a backup on a phone that syncs replaces the wardrobe here as well, and
on every phone that syncs with it: whatever the backup does not have is
deleted everywhere. To restore on one phone only, stop syncing on it first,
under the phone's **Settings → Home Assistant**.

The sync port answers nothing but sync, and only to a phone that has the code.
**Make a new code** in Settings unpairs every phone using that wardrobe's code at once — the way to shut
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
