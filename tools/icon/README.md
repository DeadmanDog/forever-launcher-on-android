# Forever Launcher artwork

The app icon, logo and splash emblem are the project's own artwork (GPL-3.0 with the app). No Blizzard artwork is used.

- Master: `app/src/main/res/drawable-nodpi/forever_emblem_042.png` (transparent; the splash uses it directly).
- `package_042.py` (via `build_icons.sh`) checks the master's hash and writes the store, notification and legacy icons.
- The adaptive launcher icon uses per-density bitmaps, `mipmap-*/ic_launcher_foreground_042.png` and
  `ic_launcher_monochrome_042.png` (108/162/216/324/432 px), on the `ic_launcher_background` drawable.
- `make_forever_icon.py` is the earlier design tool; it uses Cinzel (SIL OFL 1.1, `Cinzel-OFL.txt`). Running it does not
  change the shipped master.

```sh
PYTHON=/path/to/python-with-pillow tools/icon/build_icons.sh /some/preview/folder
```
