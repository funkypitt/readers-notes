# Reader's Notes

Plain-text notes for Android, black and white, in the family of
[Reader's Launcher](https://github.com/funkypitt/readers-launcher),
[Reader's Tasks](https://github.com/funkypitt/readers-tasks-android),
[Reader's Calendar](https://github.com/funkypitt/readers-calendar) and
[Reader's Feeds](https://github.com/funkypitt/readers-feeds).

One list, one page. A note is a text file; its first line is its title. The list shows the
title, when it changed and the next line. Tap to write, long-press to share or delete. The
⋯ menu finds, syncs, flips white on black. No formatting, no folders, no colours.

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

## Install

From the [F-Droid repo](https://funkypitt.github.io/fdroid-repo/) or the APK attached to a
release. Build with `./gradlew assembleDebug` (JDK 17+, Android SDK 35).

## Licence

MIT.
