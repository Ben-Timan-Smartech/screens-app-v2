# Samsung screens: setting up the web player

The web player runs the Screens playlist directly on a Samsung signage screen
(for example the QM43C), with no media player box. The screen opens a web page
from our server in its built-in **Web Browser**.

Custom App isn't used: it only installs signed Samsung apps, and says "Unable to
connect to the server" when given a web page (tested on a QM43C, 24/11/2026).

## Each screen's address

The short form is easiest to type on a remote:

```
https://screens.smartechworld.com/tv/holt/L1
```

At Holt Renfrew Yorkdale the addresses are `L1` to `L6` and `R1` to `R6`, six to
a set. A set is two strips of three: 1 to 3 down the left strip, 4 to 6 down
the right. Each becomes the screen's name in the CMS
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

1. In the first set-up, choose **Custom App** as the player, leave its address
   blank and skip the **RM Server** page.
2. **Home**, highlight **Web Browser**, press down, **Settings**:
   - **Home Page**: Custom, then **Enter URL** with this screen's address
   - **Refresh Interval**: Off
   - **Zoom**: 100%
   - **Advanced Settings → General → Hide Tabs and Toolbar automatically**: Use
3. **System → Auto Source Switching**: off.
4. **System → Power Control**: **Auto Power On** on, **Auto Power Off** off.
5. Turn the **screen saver** off.
6. Open **Web Browser**. Then switch the screen off at the wall and back on: the
   browser reopens by itself on its Home Page.

One remote can control several screens at once, so cover or unplug the others
while typing an address.

The menu names come from the QB43C manual and can differ between models.

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

## Stored content

Each video, and the splash, is downloaded once and played from the screen's own
storage, so a dropped network doesn't stop it. The readout's "Stored" line shows
how many files are there and whether the one on screen is playing from the
screen or the server. The screen also sends that to the CMS with each check-in.

If the browser can't play a stored file, the player streams it and tries stored
files again a day later. **Clear cache** deletes the stored files.

## Sync

Screens in a group cut between videos on the same moment of the group's clock.
A new playlist or a restart waits a few seconds (until the slowest screen has
checked in), keeps playing the current loop meanwhile, then every screen cuts
to the new first video together. Each screen reports how far it is from the
group clock, so the CMS can show whether the group is in step.

## Known limits (v1)

- **Stored content and sync aren't proven on a Samsung screen yet.** In the
  readout, "Stored" should count up to all files and say "playing from this
  screen", and "fine speed control" should say yes.
- **The splash isn't synced.** Only a playlist in a sync group is.
- **Seamless changes** need the screen to open the next video while the
  current one plays, so it runs two videos at once for a few seconds. If it
  can't, the player falls back to one by itself and there's a short blank at
  each change. The readout's "Next" line says which. Adding `?gapless=0` to
  the address (or `&gapless=0`) forces one.
- **Starting with no network** depends on the browser supporting a service
  worker, which keeps the page itself. Without one, a screen that's already
  playing carries on, but can't start again until the network is back.
- **Item lengths are whole seconds.** The CMS stores each video's length
  rounded, so a 6.2 s video holds its last frame for the rest of its 7 s slot.
  Every screen in the group does the same, so they stay in step.
- **4K portrait video** may be too much for the screen's browser. If it
  stutters, use 1080p copies.
