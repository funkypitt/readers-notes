![Reader's Notes](docs/banner.png)

# Reader's Notes

Plain-text notes for Android, black and white, in the family of
[Reader's Launcher](https://github.com/funkypitt/readers-launcher),
[Reader's Tasks](https://github.com/funkypitt/readers-tasks-android),
[Reader's Calendar](https://github.com/funkypitt/readers-calendar) and
[Reader's Feeds](https://github.com/funkypitt/readers-feeds).

One list, one page. A note is a text file; its first line is its title. The list shows the
title, when it changed and the next line. Tap to write, long-press to share or delete. The
⋯ menu finds, syncs, flips white on black. No formatting, no folders, no colours.

## Dictation

At the bottom of the list, two ways into a note, one tap each: **+ new note** to write it,
**● dictate** to say it. The same row sits under every open note. While you speak the row
shows the running time, a tap on ■ ends it, and the words arrive a moment later where the
cursor is (or at the end of the note if you have left it). The microphone stays open with the
screen off.

The words are written on the phone by [whisper.cpp](https://github.com/ggerganov/whisper.cpp)
— nothing is sent anywhere. The model is fetched once (the careful one, 574 MB, by default;
an ordinary one of 190 MB, four times faster, is in the settings) and shared with Reader's
Recorder, Audio Player and Podcasts when one of them already holds it. The sound itself is
only a means: it is deleted as soon as its text is in the note.

`content://com.freedomfighter.readersnotes/notes/dictate` (VIEW) opens a new note with the
microphone open, for a launcher.

## Sync

Notes live in a folder on any WebDAV server, one `.txt` file each, so a desktop or another
phone sees plain files. For Infomaniak kDrive the server is
`https://<ID>.connect.kdrive.infomaniak.com` (the ID is the number in the kDrive web
address), the username is your Infomaniak login, and the password an application password
when two-factor authentication is on. Nextcloud and other servers take their usual address.
The folder (default `Notes`) is created if missing. Settings › the four lines at the top.

The sync runs when the app opens, when a note is left, and from the ⋯ menu. Etags decide
who moved: a note changed here and untouched there is uploaded; changed there and untouched
here is downloaded; changed on both sides keeps the server's text as a second note
("… (server copy)") and uploads yours. Deleted here, deleted there, unless the other side
changed it since. The status line under the list says what happened, or what went wrong.

Shared text from another app becomes a new note.

A note often holds a phone number, a mail address or a link. The ⋯ menu of a note (and its long
press in the list) offers them: "call …", "write to …", "open …" — the dialer opens with the
number ready, never calling by itself. Dates, prices and room numbers are left alone. The note
itself stays a plain text field, where a tap places the cursor and a long press selects and copies.

## Widgets

Two standard home-screen widgets for any launcher, black and white: the latest note (one line,
+ for a new one) and the latest notes as a list. Tap a note to open it.

## Install

From the [F-Droid repo](https://funkypitt.github.io/fdroid-repo/) or the APK attached to a
release. Clone with `--recursive` (the speech code is the git submodule `speech/`, [readers-speech](https://github.com/funkypitt/readers-speech)); build with `./gradlew assembleDebug` (JDK 17+, Android SDK 35, NDK 27.1, CMake 3.22.1).

## Crédits / Credits

© 2026 Pierre Gallaz. Développé avec [Claude Code](https://claude.com/claude-code) (Anthropic).
Licence MIT, voir `LICENSE`.

© 2026 Pierre Gallaz. Developed with [Claude Code](https://claude.com/claude-code) (Anthropic).
MIT licence, see `LICENSE`.

## Captures d'écran

<img src="docs/screenshot-1.png" width="30%"> <img src="docs/screenshot-2.png" width="30%">
