# Privacy policy — Reader's Notes

*Last updated: 8 November 2026*

Reader's Notes (`com.freedomfighter.readersnotes`) is published by Pierre Gallaz. It keeps plain-text notes, typed or dictated, with pictures and drawings, on your phone. It is free, has no ads, no account and no tracking.

## Data the app collects

None. The app does not collect, store or transmit any personal data to its publisher or to anyone else, and it contains no analytics, advertising or crash-reporting library.

## Dictation

Dictation uses the microphone only while you have started it and until you stop it; a notification shows it is running. The sound is kept on the phone as raw samples until its words have been written into the note, then deleted. Speech is turned into text on the phone itself, by the whisper.cpp model; no sound and no text ever leaves the phone for that.

The speech model is a file downloaded once, when you first dictate, from huggingface.co (the publisher of whisper.cpp's models); the download is a plain file request that carries no identifier. The model is shared, on the phone, with the other Reader's apps signed by the same publisher, so that it is downloaded once.

## Network use

Apart from that model download, the app uses the network only if you set up a sync folder: a WebDAV address (for example your own kDrive or Nextcloud) with the user name and application password you type. The app then reads and writes your notes, as `.txt` files, and their pictures in that folder, and nowhere else. The sync runs when you open the app, when you leave a note, and when you ask for it. No other server is contacted. Plain HTTP is accepted only towards your own machine (localhost).

## Data kept on the phone

Your notes, their pictures and drawings, your settings and the sync credentials stay in the app's private storage on the phone. They are deleted when the app is uninstalled. Android's own backup may copy the notes and the settings (not the pictures, nor a dictation still waiting for its words) to the Google account configured on the phone; that is controlled by the phone's backup settings, not by the app.

The app can export your sync credentials to a small file, only when you ask it to, so that another Reader's app on the same phone can use the same server; that file goes where you choose to send it. A note exported as a PDF is saved where you choose and offered to the share menu.

## Sharing with other apps

Reader's Launcher, when installed and signed by the same publisher, may read the list of your notes to show them in a tile. Text or a picture shared to the app from another app becomes a note. No other app has access to your notes.

## Permissions

The app asks only for what its job needs: the microphone, for dictation; a foreground service with a quiet notification and a wake lock, so that dictation and transcription go on while the screen is off; notifications, to show them; the network, for the model and the optional sync.

## Children

The app is not directed at children and collects no data from anyone.

## Contact

Pierre Gallaz — pierregallaz@gmail.com

Source code: https://github.com/funkypitt/readers-notes
