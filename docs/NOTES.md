# Notes

Reference material moved out of the README.

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
