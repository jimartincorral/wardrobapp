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
