# CoveShelf user guide

CoveShelf organizes media stored on your NAS and opens it directly over your
network. Your files stay on the NAS; no media server or transcoding box is needed.

## Connect one shared parent folder

In **Sources**, choose **Add NAS** (**Add NAS Source** on Android), then select a discovered NAS or enter
its address. CoveShelf tries guest access first. If your NAS requires an account,
choose **Sign in** and enter its username and password in the app. A read-only NAS
account is recommended; keep its password out of chat, screenshots, and issue reports.

Choose the shared folder, then open the parent folder containing your media.
Choose **Use this folder & scan** to save it and start scanning. **Back** returns
through the folders you opened. If a folder cannot be opened, retry or go back
without re-entering the connection.

If discovery does not list your NAS, its hostname or IP address can still work.
Addresses such as `smb://media-nas` and Windows network paths such as
`\\media-nas\Media` are accepted for the server address; select the share and
media folder in the following steps. Connection options include a custom SMB port
when your NAS needs one. If it restricts share browsing, enter the share name manually.

If the share itself is named `Media`, select it and use its top-level folder.
If `Media` is inside another share, select that share and choose its `Media`
folder. CoveShelf scans the selected folder and all its subfolders recursively.
You do not need to add each movie, show, or category separately.

A source pointing to `Media/Movies` finds only that folder and its descendants.
It cannot find sibling `Music`, `TV Shows`, or `Photos` folders. Edit the source to use the
common `Media` parent, then scan again.

## Choose what to scan or ignore

Open **Sources → Edit** for the NAS. Use **Choose scan folder** on Android or
**Choose a media folder** on Apple TV to browse the share, open subfolders, and
select **Use this folder** or the share root.

Under **Ignored folders and files**, open the folder/file picker. Choose
**Ignore folder** to exclude that folder and all its descendants, or **Ignore
file** to exclude only that file. You can also enter a relative path manually.
For example, if the selected scan folder is `Media`, use `Projects` or
`Movies/Extras/trailer.mp4`, without the NAS address or `Media` prefix.

Choose **Save & Scan** to apply the rules. Already indexed excluded items
disappear from this source's library immediately; the NAS files and their saved
playback history are kept. To restore an item, choose **Include again** (or
**Stop ignoring** on Apple TV), save, and let the scan finish. Rules apply only
to that NAS source. If another source scans the same files, set its rules too.

Changing the NAS address, port, share, or scan folder clears its relative
exclusions so they do not apply to unrelated folders. Select the exclusions
again for the new location.

Software dependency folders such as `node_modules` and TypeScript declaration
files (`.d.ts`) are skipped automatically. Other `.ts` files are checked for
MPEG transport-stream content before being added, because `.ts` can also mean
TypeScript source code. Rescan an existing source to clear previously imported
code files from the library.

## Use clear names

For example:

```text
Media/
  Movies/Arrival (2016)/Arrival (2016).mkv
  Documentaries/Documentary Title (2020)/Documentary Title (2020).mp4
  TV Shows/Show Name (2020)/Season 01/ShowName.S01E01.mkv
  Music/Artist/Album/01-Song.mp3
  Photos/Family Trip/Beach.jpg
```

Include a title and release year for movies and documentaries. For television,
include a season/episode marker such as `S01E01`. Keep each show's seasons under
one show folder. Browse a show's master poster, choose a season, then select an
episode tile to play it.

Names such as `[1x02]`, `S01 E02`, and `S01xE02` also work, including folders
named `Show Name Season 1`. Under `TV Shows`, the show folder supplies the shared
title even when episode filenames use a different title for each season. Local
grouping does not require an artwork key or another scan after an app update.
Unnumbered extras need a season/episode marker, such as `S00E01`, to appear in
the show's **Specials** season.

A numbered season folder can supply the season when the filename supplies the
episode, as in `TV Shows/24/Season 02/E03.mkv`. Without matched genres,
`Documentary Films`, `Music Videos`, and `Concerts` also work as category folders.
A concert video can fetch a poster and description like other films.

For audio music, consistent artist and album folders help identify the files.
TMDB is not an audio album catalog: its Music category describes screen content
such as music films, not MP3 artist, album, or cover-art information.

## Browse photo albums

Keep related photos in a folder, such as `Media/Photos/Family Trip`. Include that
folder or its parent in your NAS source and scan again. Open **Photos**, choose
an album, and select a thumbnail to view the photo full screen. **Previous** and
**Next** move through the album; **Back** returns to its thumbnails.

Albums follow the NAS source and the folder containing each photo. Both apps
recognize JPEG (`.jpg`, `.jpeg`), PNG, WebP, HEIC, and HEIF files. Viewing depends
on the device's image decoder; a damaged file or unsupported encoding may not
open. Keep the NAS reachable while browsing, and use **Try again** if a photo
could not be loaded. Files larger than 64 MB cannot be opened; large images are
reduced for display without changing the original. Photos are read directly
from the NAS and are not sent to TMDB.

## Add metadata and artwork

For personal use, obtain a free TMDB API key or read access token from
themoviedb.org under **Settings → API**. Enter it in CoveShelf's **Settings** and
choose **Save Key & Fetch Missing Metadata**. Do not paste the key into chat.
Matching sends the parsed title and year to TMDB; your media files stay on the NAS.
See [TMDB's API FAQ](https://developer.themoviedb.org/docs/faq) for personal-use terms.

Matching provides real titles, descriptions, posters, and categories. A matched
movie's metadata takes priority over folder-name guesses when classifying movies,
documentaries, and music-related films.

Automatic matching checks the title before using the release year to rank
candidates. For short titles such as *Up*, the normalized display title or
original title must match exactly.
If a previously matched film has the wrong poster or genres, open its detail
screen, choose **Edit**, and use **Fix Match** to select the correct film.

Without a key, new unmatched items use filename/folder information and placeholder
artwork. A folder label alone cannot identify the correct movie. If an item is
unknown or incorrectly matched, check its title and year and use the manual match
correction. Different films or series can share the same name.

## Browse with filters

Open a library section and choose **Genre**, **Source**, or **Decade** to see
the available choices. Check as many as you want, then choose **Apply**.
**Back** cancels the changes in the open list.

For example, choosing *Comedy* and *Drama* includes titles in either genre.
Adding the *1990s* narrows those titles to that decade. Choose **Clear filters**
to see the whole section again. **Sort** changes the order of the results.

## Check the scan and playback

Keep the NAS reachable while scanning. Progress and partial results can appear
before completion; they do not mean every folder has finished. Check the status
and the last successful scan time. If a scan fails, correct the connection or
credentials and retry.

CoveShelf does not transcode. A filename extension identifies a candidate
format; the file must contain real media, and the device must support the
audio/video codecs inside it. Unsupported formats show an explanation instead
of Play controls. Apple TV also checks the actual asset before starting playback.

| Platform | Video candidates | Audio candidates |
| --- | --- | --- |
| Android TV | MP4, M4V, MOV, MKV, WebM, AVI, MPEG-TS, MPEG-PS, FLV | MP3, M4A, AAC, FLAC, WAV, Ogg, Opus |
| Apple TV | MP4, M4V, MOV | MP3, M4A, AAC, FLAC, WAV, AIFF/AIF |

This table describes the current app's file playback, not every format supported
by other players. Android's AVI support depends on its extractor and device
codecs; FLV seeking is limited. WMV, ISO, and M2TS are cataloged but cannot be
played by these built-in players. WMA cannot play on either platform, and AIFF/AIF
cannot play in this Android build. Unsupported real media can remain in the
library for organization.

The `.ts` suffix is shared by MPEG transport-stream video and TypeScript source
code. `.d.ts` is a TypeScript declaration file and is never media. Scanning checks
the packet structure of ambiguous `.ts` files; Android repeats that check before
playing an existing entry. Android's TS player requires standard 188-byte packets;
192-byte M2TS and 204-byte variants are not enabled for playback. The player then
checks whether it can decode its tracks.
Standalone TS files are not enabled for playback in the current Apple TV app.

Format references: [Android Media3 progressive formats](https://developer.android.com/media/media3/exoplayer/progressive),
[Media3 codec support](https://developer.android.com/media/media3/exoplayer/supported-formats),
[the app's Media3 1.8.0 extractors](https://github.com/androidx/media/blob/1.8.0/libraries/extractor/src/main/java/androidx/media3/extractor/DefaultExtractorsFactory.java),
[its TS packet parser](https://github.com/androidx/media/blob/1.8.0/libraries/extractor/src/main/java/androidx/media3/extractor/ts/TsExtractor.java),
[Apple asset playability](https://developer.apple.com/documentation/avfoundation/avasset/isplayable),
and [TypeScript declaration files](https://www.typescriptlang.org/docs/handbook/2/type-declarations.html).
