# Samsung screens: setting up the web player

The web player runs the Screens playlist directly on a Samsung signage screen
(for example the QM43C), with no media player box. The screen installs a small
app from our server through its built-in **Custom App** setting, and the app
opens the player.

Custom App doesn't open web pages. It asks the address for `sssp_config.xml`,
then downloads the signed app (`Screens.wgt`) named in it. If there's nothing
there, or the screen's date is wrong, it says it can't connect to the server.

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

1. Set the **date and time** (Menu → System → Time). A wrong date stops the
   install.
2. Menu → **System → Play via → Custom App**.
3. Press **SOURCE** and pick **HDMI**, then HOME → **App Management** →
   **Install Custom App**, and enter the screen's address (above). If the
   install can't find the app, try the address with a `/` on the end.
4. Under **System → Power Control**: **Auto Power On** on, **Auto Power Off** off.
5. Turn the **screen saver** off.
6. Switch the screen off at the wall and back on. It should open the player
   by itself.

The menu names differ slightly between firmware versions. These come from
signage vendors' guides for the same range and haven't been checked on a QM43C.

If it says **Unable to install**, the address works and the certificate is the
problem: check that this screen's Unique Device ID is in it.

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

## Sync

Screens in a group cut between videos on the same moment of the group's clock.
A new playlist or a restart waits a few seconds (until the slowest screen has
checked in), keeps playing the current loop meanwhile, then every screen cuts
to the new first video together. Each screen reports how far it is from the
group clock, so the CMS can show whether the group is in step.

## Building and signing the app

The app's source is in `samsung-app/`. It's a shell that opens the player and
opens it again if it doesn't start or goes quiet, so it rarely changes: the
player itself updates from the server.

Each screen has its own package, because Custom App doesn't tell an app which
address it came from. All of them use one Samsung certificate, which must list
every screen's **Unique Device ID** (Menu → Support → Contact Samsung).

1. In Tizen Studio's Certificate Manager, make a **Samsung → TV** profile. It
   needs a Samsung account. Add every screen's ID to the distributor
   certificate. Keep the author certificate and its password safe and out of
   this repo: an update signed with a different one won't install over the old.
2. Raise `samsung-app/VERSION`.
3. `python samsung-app/build.py --store holt --codes L1-L6,R1-R6 --profile <name>`
4. Commit `app/tv/apps/`, merge, and restart each screen.

Adding a screen later means adding its ID to the certificate and building again.

## Known limits (v1)

- **Not yet tested on a real Samsung screen.** Check the debug readout on the
  first screen: "Cache" should count up to all videos, and "fine speed
  control" should say yes.
- **The certificate is unproven on the QM43C.** Reports on other models say a
  partner-level certificate tied to each screen's ID is needed.
- **Seamless changes** need the screen to open the next video while the
  current one plays, so it runs two videos at once for a few seconds. If it
  can't, the player falls back to one by itself and there's a short blank at
  each change. The readout's "Next" line says which. Adding `?gapless=0` to
  the address (or `&gapless=0`) forces one.
- **Offline play** depends on the screen supporting the browser's offline
  storage (a service worker). If it doesn't, the screen streams from the
  server and stops when the network drops.
- **Item lengths are whole seconds.** The CMS stores each video's length
  rounded, so a 6.2 s video holds its last frame for the rest of its 7 s slot.
  Every screen in the group does the same, so they stay in step.
- **4K portrait video** may be too much for the screen's browser. If it
  stutters, use 1080p copies.
