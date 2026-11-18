# Study Timer

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
In Musicolet, open the **Playlists** tab, then tap the **⋮** menu on a playlist (or the menu on the Playlists screen) and look for **Export**. Choose **M3U file** and remember the folder it saves to, or choose **Android media storage**. Then in Study Timer pick "Playlist file" or "Playlist saved on the phone". The first time, allow the **Music and audio** permission, which the app needs to find the songs.
- **Start** runs Work → beep → Break → beep → Work … until you press **Stop**.
- While running: **Pause/Resume**, **Skip ›** (go to the next timer now), and **Stop music / Play music** for the current timer.
- **🌐 language button** (top right): Phone language, English or Deutsch. By default the app follows the device language, so it is German on a German phone. If you switch language while a timer is running, the notification changes after you press Stop.
- The timer keeps running with the screen off. You can also control it from the notification.

## Getting the APK (choose one)

### Option A: GitHub (no software to install)
1. Create a free GitHub account, then a new **private** repository.
2. Click **"uploading an existing file"** and drag in everything from this folder, **including the hidden `.github` folder**. (If your file browser hides it, turn on "show hidden files" first.)
3. Open the **Actions** tab and wait about 3 minutes for "Build APK" to turn green.
4. Click the run and download **StudyTimer-apk** under *Artifacts*. Unzip it to get `app-debug.apk`.

### Option B: Android Studio (on a PC/Mac)
1. Install Android Studio, then choose **Open** and select this folder.
2. Let it sync (the first time takes a few minutes).
3. Menu **Build → Build App Bundle(s)/APK(s) → Build APK(s)**. The APK is in `app/build/outputs/apk/debug/`.

## Installing on the Galaxy S24 / Tab S6
1. Copy `app-debug.apk` to the device and tap it in **My Files**.
2. Allow "Install unknown apps" for My Files when asked.
3. Open Study Timer and allow notifications.
4. **Samsung tip (important):** go to Settings → Apps → Study Timer → Battery and set it to **Unrestricted**, so Samsung never pauses the timer in the background.

The beep plays on the **alarm volume**, so it can be heard even when the phone is on silent. Check that the alarm volume isn't set to zero.
