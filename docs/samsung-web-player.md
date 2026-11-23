# Samsung screens: setting up the web player

The web player runs the Screens playlist directly on a Samsung signage screen
(for example the QM43C), with no media player box. The screen opens a web page
from our server through its built-in **Custom App** setting.

## Each screen's address

```
https://screens.smartechworld.com/tv/?store=tmrw-holt-yorkdale&code=COL.L-1
```

- `store` is the location's id. Holt Renfrew Yorkdale is `tmrw-holt-yorkdale`.
- `code` is the screen's position, for example `COL.L-1` to `COL.L-6` and
  `COL.R-1` to `COL.R-6`. It becomes the screen's name in the CMS and keeps
  the screen's identity, so use a different code on every screen.
- Add `&debug=1` to show the readout, or press **OK / Enter** on the remote to
  toggle it. It shows the screen id, clock offset, drift, what's playing and
  how many videos are stored on the screen.

## On the screen

1. Menu → **System → Play via → Custom App**.
2. Enter the screen's address (above) when asked.
3. Under **System → Power Control**: **Auto Power On** on, **Auto Power Off** off.
4. Turn the **screen saver** off.
5. Switch the screen off at the wall and back on. It should open the player
   by itself.

The menu names can differ slightly between firmware versions.

## In the CMS

1. Each screen appears at its store under its `code` after the first start.
2. Put the six screens of a column in one **sync group** (Sync groups, or the
   screen's page). Each column gets its own group, for example `holt-col-l`
   and `holt-col-r`.
3. Push the column's playlist to the group.
4. If the picture is on its side, set the screen's **Rotation** to 90° or
   270°. If the screen's own menu has a portrait content setting, that works
   too; use one or the other, not both.

## What the commands do

Restart, Reboot and Update all reload the page, which also picks up a newer
version of the player. Refresh fetches the playlist straight away. Clear cache
deletes the stored videos and reloads.

## Known limits (v1)

- **Not yet tested on a real Samsung screen.** Check the debug readout on the
  first screen: "Cache" should count up to all videos, and "fine speed
  control" should say yes.
- **Offline play** depends on the screen supporting the browser's offline
  storage (a service worker). If it doesn't, the screen streams from the
  server and stops when the network drops.
- **Item lengths are whole seconds.** The CMS stores each video's length
  rounded, so a 6.2 s video holds its last frame for the rest of its 7 s slot.
  Every screen in the group does the same, so they stay in step.
- **4K portrait video** may be too much for the screen's browser. If it
  stutters, use 1080p copies.
