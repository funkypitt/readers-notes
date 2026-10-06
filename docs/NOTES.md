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

Another server or folder (typed in the settings, or brought by a credentials file): the store
remembers the folder URL it was last synced with (`Index.place`), and `Sync.run`, once the new
place has answered, calls `NotesStore.syncingWith`: every note is treated as never synced, kept
and uploaded there, none is deleted (the desktop's `forget_server`). A note whose file is already
there with the same text takes that file instead of a second one, so going back to a place does
not double the notes. Emptying the server line turns the sync off ("on this phone only"); the
notes stay. Tests: `SyncTest` (a server kept in memory behind the `Dav` interface).

Shared text from another app becomes a new note.

A note often holds a phone number, a mail address or a link. The ⋯ menu of a note (and its long
press in the list) offers them: "call …", "write to …", "open …" — the dialer opens with the
number ready, never calling by itself. Dates, prices and room numbers are left alone. The note
itself stays a plain text field, where a tap places the cursor and a long press selects and copies.

## Widgets

Three standard home-screen widgets for any launcher, black and white: the latest note (one line,
+ for a new one), the latest notes as a list, and **one note** (1.7.0): a note chosen when the widget
is placed (`NoteWidgetConfigure`, the launcher's configure activity, « + new note » creates one and
opens it), shown title + text on about half a screen (minHeight 350dp = 10 cells in Reader's
Launcher), tap to edit; every keystroke redraws it through `NotesStore.saveIndex` → `NotesWidgets.refresh`.
The widget → note mapping lives in the "widgets" preferences (`note_<widgetId>`), cleared in `onDeleted`;
a deleted note leaves « this note was deleted ». `widgetFeatures="reconfigurable"` lets the launcher
offer « configure » again (the current note is marked ●).

## Folders (1.6.0, optional)

Setting "dossiers" (`Settings.useFolders`, pref `folders`, off by default). On: the first page is
`FoldersScreen` — « all notes » (filled glyph, count), each folder (outline glyph, count), « new
folder » (dashed) — as in Reader's Scanner but as a list; a folder opens `NotesScreen(folder)`,
where new notes and dictations go; a note's menu has « move to a folder »; a folder's long press:
rename, delete (its notes stay, in « all notes »).

Store: `Note.folder` (here) and `Note.remoteFolder` (on the server); `Index.folders`
(`NoteFolder(name, onServer)`) and `goneFolders` (deleted/renamed here, to remove there once
empty). Sync (same algorithm as the desktop 1.3.0): a folder = a subfolder of the synced folder,
one level deep; notes keyed "folder/name". Subfolders are synced when the setting is on OR
anything was ever put in a folder (`usesFolders`), so turning the setting off never drops notes;
otherwise exactly as before (the root only). A folder `onServer` and missing there = deleted there
(unless a dirty note still needs it); one deleted/renamed here is DELETEd there once no file is
left in it (someone else's files keep it, and it comes back). Folder names: `folderNameOf`, the
file-name rule, 60 characters.

Tested 2026-09-26 against wsgidav (emulator + headless desktop): off = subfolder ignored; on =
subfolder pulled as a folder; new folder + note; move; rename; delete here; delete there; off
after use keeps syncing; phone ↔ desktop see the same folders.

Prompts now open a pre-filled value selected (the Reader's rule): `TextPrompt` keeps a TextFieldValue.
