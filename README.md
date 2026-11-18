# PoFocus

A minimal Pomodoro timer for Android: one Work timer and one Break timer (5–60 min, 5-minute steps), optional music per timer, and a beep at every switch.

## Using it
- **− / +** on each card changes the time in 5-minute steps.
- **Choose music** offers three choices:
  - **Songs**: pick one or several audio files. Long-press to select more than one.
  - **Playlist file (.m3u)**: a playlist exported from Musicolet or another player.
  - **Playlist saved on the phone**: playlists that Musicolet exported to Android media storage.
- **▶** previews the music and **✕** removes it. A timer with no music runs silently.
- Playlists play in order and start over after the last song. Each session continues with the song where the last one stopped. While a timer runs, **⏭** skips to the next song.
- The app saves a copy of the playlist when you choose it. If you change the playlist in Musicolet later, export it and choose it again here.

### Getting a playlist out of Musicolet
In Musicolet, open the **Playlists** tab, then tap the **⋮** menu on a playlist (or the menu on the Playlists screen) and look for **Export**. Choose **M3U file** and remember the folder it saves to, or choose **Android media storage**. Then in PoFocus pick "Playlist file" or "Playlist saved on the phone". The first time, allow the **Music and audio** permission, which the app needs to find the songs.
- **Start** runs Work → beep → Break → beep → Work … until you press **Stop**.
- While running: **Pause/Resume**, **Skip ›** (go to the next timer now), and **Stop music / Play music** for the current timer.
- **🌐 language button** (top right): Phone language, English or Deutsch. By default the app follows the device language, so it is German on a German phone. If you switch language while a timer is running, the notification changes after you press Stop.
- **Music controls everywhere:** while music plays, it appears in the notification shade / media panel and on the lock screen (play/pause, previous, next, seek, and "Stop timer"). Bluetooth and headphone buttons work too. Unplugging headphones pauses the music, and calls pause it.
- **Change things while it runs:** − / + on the running timer makes it shorter or longer right away. Choosing new music for the running timer switches to it immediately.
- **Closing the app** (swiping it away in recent apps) stops the timer and the music. Pressing Home keeps it running.
- The timer keeps running with the screen off. You can also control it from the notification.

## Getting the APK (choose one)

### Option A: GitHub (no software to install)
1. Create a free GitHub account, then a new **private** repository.
2. Click **"uploading an existing file"** and drag in everything from this folder, **including the hidden `.github` folder**. (If your file browser hides it, turn on "show hidden files" first.)
3. Open the **Actions** tab and wait about 3 minutes for "Build APK" to turn green.
4. Click the run and download **PoFocus** under *Artifacts*. Unzip it to get `PoFocus.apk` (phone/tablet) and `PoFocus-Watch.apk` (Galaxy Watch).

### Option B: Android Studio (on a PC/Mac)
1. Install Android Studio, then choose **Open** and select this folder.
2. Let it sync (the first time takes a few minutes).
3. Menu **Build → Build App Bundle(s)/APK(s) → Build APK(s)**. The APK is in `app/build/outputs/apk/debug/`.

## Installing on the Galaxy S24 / Tab S6
1. Copy `app-debug.apk` to the device and tap it in **My Files**.
2. Allow "Install unknown apps" for My Files when asked.
3. Open PoFocus and allow notifications.
4. **Samsung tip (important):** go to Settings → Apps → PoFocus → Battery and set it to **Unrestricted**, so Samsung never pauses the timer in the background.

The beep plays on the **alarm volume**, so it can be heard even when the phone is on silent. Check that the alarm volume isn't set to zero.

## Watch app (Galaxy Watch4 or newer)
- Works on its own: Work/Break timer with 5-minute steps, − / + while running, vibration and beep at each switch, and a notification with Pause/Stop.
- Scroll with the bezel or crown, or by swiping, to reach the Work/Break minutes.
- With the phone nearby, Work/Break minutes stay the same on both. The newest change wins.
- The watch uses the watch's own language (English or German).
- The phone's music controls show up on the watch by themselves (Samsung media controller).

### Installing on the watch
The watch can't install APK files by tapping them. Use the free **Wear Installer 2** app on the phone:
1. Watch: Settings → About watch → Software information → tap **Software version** 5 times → Developer options appear.
2. Watch: Settings → Developer options → turn on **ADB debugging** and **Wireless debugging** (watch and phone on the same Wi-Fi).
3. Watch: Wireless debugging → **Pair new device** shows a code, IP and port. Pair in Wear Installer 2.
4. In Wear Installer 2, choose `PoFocus-Watch.apk` and install.
5. Afterwards turn Wireless debugging off again (saves battery).
