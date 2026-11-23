# Samsung screens: setting up the web player

The web player runs the Screens playlist directly on a Samsung signage screen
(for example the QM43C), with no media player box. The screen opens a web page
from our server through its built-in **Custom App** setting.

## Each screen's address

The short form is easiest to type on a remote:

```
https://screens.smartechworld.com/tv/holt/L1
```

At Holt Renfrew Yorkdale, `L1` to `L6` are the left column (top to bottom)
and `R1` to `R6` the right. Each becomes the screen's name in the CMS
(`COL.L-1` to `COL.R-6`) and keeps the screen's identity, so use a different
one on every screen. Capitals don't matter.

The long form does the same and works for any store:

```
https://screens.smartechworld.com/tv/?store=tmrw-holt-yorkdale&code=COL.L-1
```

- `store` is the location's id. Holt Renfrew Yorkdale is `tmrw-holt-yorkdale`.
- `code` is the screen's position. The short and long forms give the same
  screen, so either can be used.
- Press **OK / Enter** on the remote to show or hide the readout, or add
  `?debug=1` to the address (`&debug=1` on the long form). It shows the screen
  id, clock offset, drift, what's playing and how many videos are stored on
  the screen.

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
