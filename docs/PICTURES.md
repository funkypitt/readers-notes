# Pictures and drawings in a note — the shared contract

Reader's Notes (Android) and Reader's Notes desktop read and write the same notes folder, so
both follow this to the letter. Change one side only together with the other.

## In the text

A note stays one plain `.txt` file. A picture is **one line of its own**, in Markdown form:

    ![](.pictures/drawing-20261104-093012-a3f9.png)

- Recognised when the whole line, trimmed, matches
  `^!\[[^\]]*\]\(\.pictures/([A-Za-z0-9][A-Za-z0-9._-]*\.(png|jpg|jpeg|webp))\)$` (case-insensitive
  extension). Anything else is ordinary text. The alt text between the brackets is ignored;
  we write it empty.
- The path always resolves against the **root of the synced notes folder**, whatever folder the
  note is in (so moving a note between folders changes nothing).
- Names we write: `<kind>-<yyyyMMdd>-<HHmmss>-<4 lowercase hex>.<ext>`, kind `drawing` (PNG, drawn
  in the app) or `picture` (JPEG, inserted from a file / the gallery / the clipboard). A file is
  **never rewritten**: editing a drawing saves a new file with a new name and the line in the
  note changes to it.
- Inserted pictures are scaled so the long side is at most 1600 px, JPEG quality 85, EXIF
  orientation applied (no EXIF kept). Drawings: PNG, black ink on a white (opaque) background,
  at most 2000 px on the long side.

## What the picture lines change elsewhere

- Title (`titleOf` / `title_of`): the first non-empty line **that is not a picture line**.
  Nothing else → "" (the list shows the localised word "drawing" or "picture", by the kind of the
  first picture; the file name falls back to `untitled.txt` as before).
- Preview / second line, launcher provider preview, widgets, search, link detection (⋯ « call /
  write / open »), sharing as text: picture lines are left out (a picture name holds long digit
  runs that the phone-number pattern would take).
- Tags are unaffected (a picture line never holds `#`).

## On the server

- Picture files live in `<notes folder>/.pictures/` (flat, one level). Every app already skips
  subfolders whose name starts with a dot, so older versions never take it for a folder of notes.
- Sync, after the notes' own sync in the same run:
  1. `refs` = picture names referenced by any local note (live, not tombstoned, book notes
     included — they never hold any).
  2. If `refs` is empty and the server has no `.pictures`: nothing more.
  3. List `.pictures` (missing → empty). For each name in `refs` present locally and absent there:
     MKCOL `.pictures` once if needed, then PUT the bytes (Content-Type image/png or image/jpeg).
  4. For each name in `refs` absent locally: GET; a 404 is not an error (another device has not
     sent it yet) — try again next sync.
  5. Server garbage collection: a file in `.pictures` that no local note references **and** whose
     last-modified is older than 2 days → DELETE. (Any device that still has the file and a note
     referencing it uploads it again at step 3, so this heals itself.)
- Local files: Android `filesDir/pictures/<name>`, desktop `<data dir>/pictures/<name>`. A local
  file no note references and older than 2 days (file mtime) is deleted at the end of a sync, or
  at startup when sync is off.
- Picture errors never fail the notes' sync: they are counted and retried next time.

## Drawing

- Pen first: on Android `MotionEvent` tool type STYLUS uses pressure for the width; tool type
  ERASER, or the stylus button held, erases. Once a stylus has touched the page, fingers no longer
  draw (palm rejection) for the rest of that page. On the desktop, `QTabletEvent` pressure; the
  eraser end (pointer type Eraser) or a side button erases; the mouse draws at a fixed width.
- Tools, nothing more: pen, eraser, undo, and leaving the page saves (an untouched new page saves
  nothing). Opening an existing drawing loads it as the page to go on drawing on; erasing paints
  white.
