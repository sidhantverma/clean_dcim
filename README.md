# Clean DCIM

Small native Java Android app (Android 11+, targeting Android 16). No account,
network permission, MacroDroid, helper app, framework runtime, or root required.

## Install and use

1. Copy `dist/CleanDCIM-1.4.apk` to your phone and open it. Allow installation
   from that source if Android asks. This is a sideloaded, debug-signed APK.
2. Launch **Clean DCIM**. Enable **Allow access to manage all files** and return.
   Only file access is required. Notification denial, disabled notifications,
   or muted categories never block watching, including restarts after boot.
   The permissions-required screen applies only to file access.
3. Default source: `/storage/emulated/0/DCIM`. Default destination:
   `/storage/emulated/0/DCIM-Sorted`. Edit these absolute paths as needed.
   The source must be an existing DCIM folder in internal shared storage.
   The destination is created automatically and must be outside DCIM and
   Android, within the same internal shared storage.
4. Tap **Enable watch**. Existing and newly created non-camera files are
   included. Paths are locked while watching; disable to edit them.
5. On Samsung, open **Battery settings**, choose **Battery > Unrestricted**,
   and add Clean DCIM under **Settings > Battery > Background usage limits >
   Never auto sleeping apps** (names vary by One UI version).
6. Tap **Disable watch** in the app or notification to stop moving files.
7. Notifications are optional. **Error notification settings** lets you grant
   notification permission or open the separate **Move errors** category.
   To receive errors only, allow app notifications, enable **Move errors**, and
   disable **DCIM watcher** in Android's notification categories. Samsung may
   require enabling **Settings > Notifications > Advanced settings > Manage
   notification categories for each app** to expose category controls.
   If app notifications are globally disabled, error alerts cannot appear;
   failures still appear in the app. Android still requires a foreground-service
   notification internally and may show the app in its active-apps interface.
8. **Show log / Hide log** expands or collapses the persistent activity log.
   Entries are newest first, with local date/time (including milliseconds):
   `Created <full source path>`, `Moving <full destination path>`,
   `Moved <actual destination path>`, or
   `Error Moving to <destination path> Reason: <message>`. Collision-renamed
   paths are included. `Created` means first detected by the watcher, including
   files already present when enabled; it is not the file's historical creation
   timestamp. Camera is excluded. General watcher failures also appear.
   **Clear log** deletes saved entries without stopping the watcher.
   Entries aged 72 hours or more are purged on writes, reads, watcher scans,
   and every minute while this screen is open. If neither app nor watcher is
   running, cleanup happens next time either runs. Logs survive app restarts
   and updates, but are not backed up. Older/newer controls page through 200
   entries at a time; new activity returns the view to the newest entries.
   `Waiting <source> Reason: <message>` identifies temporary filenames, recent
   writes, or Android MediaStore pending state. Repeated identical reasons are
   not logged every scan. `Skipped` reports a file deleted/renamed before a move.
   The status shows the last scan time and count of files waiting at readiness
   checks (files still undergoing stability sampling may not be counted).

## Behavior and safety

- Files under the top-level `Screenshots` folder (case-insensitive, including
  nested files) stay for at least **2 minutes after first detection** to allow
  sharing. This includes existing screenshots when watching starts. The log
  shows a sharing-grace waiting reason. Other files retain the 3-second quiet
  period. Screenshot writes must also settle for 3 seconds, and Android pending
  state still blocks movement. Retries/stability checks can add a few seconds.
  Reads for sharing do not restart the grace period; sharing that lasts beyond
  two minutes is not protected. Restarting the watcher starts a fresh grace
  period for remaining screenshots.

- The top-level `Camera` folder and everything under it are excluded,
  case-insensitively, including camera videos. All empty non-Camera subfolders
  under DCIM are removed while watching, including pre-existing empty folders.
  Cleanup runs bottom-up after scans; DCIM itself and Camera are never removed.
  Only single empty directories are deleted, never recursively; nonempty folders,
  including folders containing deferred recordings, are retained. Other apps
  can recreate folders, which the watcher will observe again.
- Other files retain their paths: `DCIM/Screenshots/a.png` becomes
  `DCIM-Sorted/Screenshots/a.png`. Files directly in DCIM move directly into
  the destination. Hidden files, including `.nomedia`, are included.
- Waits at least three quiet seconds. Changing files, `.pending-` temporary
  filenames, and MediaStore pending recordings are deferred. Read-only opens
  and closes from Gallery, previews, or media indexing do not reset this delay.
  FileObserver cannot distinguish read and write opens reliably; no open-file
  counters are used. Files first found at startup
  or by reconciliation require two stable observations three seconds apart.
- Files are moved within the same shared-storage volume, not copied and deleted.
  Collisions receive ` (1)`, ` (2)`, etc.; existing files are not overwritten.
  Symlinks are not followed when scanning. Failures stop the watcher and show
  an error in the app and, if enabled, a notification in **Move errors**;
  sources are not deleted on a failed move. Folder-cleanup failures are reported
  the same way.
- Recursive FileObservers cover existing and new subdirectories. MediaStore
  observation and a 30-second fallback scan cover missed filesystem events.
  While any non-camera files remain, readiness is rechecked every three seconds,
  even if all are pending. It does not require another filesystem event.
  Media scanning updates the gallery after moves.
- Watching uses a `specialUse` foreground service for continuous local
  file management, not a time-limited `dataSync` service. Enabled state is saved;
  Android can restart the service after process reclamation, reboot (after
  unlock), or an app update.
- **Android cannot promise an unkillable watcher.** Force-stop, Android's active
  apps Stop button, revoked permissions, Samsung sleeping policies, and device
  sleep can stop or delay it. Reopen after a force-stop; re-enable if needed.
  Three seconds is a minimum quiet period, not a real-time deadline.
- Apps that keep files open without writing for more than three seconds and
  without MediaStore pending metadata may be moved before that app finishes.
  No timeout overrides Android's pending flag or `.pending-` filename.
  Apps that keep writing without normal write events or MediaStore pending
  metadata cannot be perfectly identified, particularly if a recording was
  already open when watching started. Avoid initially enabling watch during
  an active recording. Test with disposable media before trusting it with
  important files; retain backups.

## Build

Only a JDK 17+ and Android SDK command-line tools are needed; Android Studio is
optional. Install SDK platform 36 and build-tools 36.0.0, accept their licenses,
and set `ANDROID_HOME` to the SDK and `JAVA_HOME` to the JDK.

On Windows:

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

The APK is at `app/build/outputs/apk/debug/app-debug.apk`. Gradle downloads the
Android Gradle Plugin and JUnit. The app itself has no third-party runtime
dependencies. Unit tests cover the pure-Java movement policy.

The supplied APK is for personal sideloading, not a Play Store release.
Future updates need the same signing key to install over an existing version.
Back up the build machine's debug keystore privately if maintaining this
installation, or configure a private release keystore for long-term releases.
Do not commit signing keys. All-files access and special-use foreground
services require policy review if publishing to Google Play.

## GitHub releases

The release workflow runs unit tests and publishes a signed APK and its SHA-256
checksum when a version tag is pushed. To configure it:

1. Create a private release keystore **outside this repository** using a JDK:

   ```powershell
   keytool -genkeypair -v -keystore "$HOME\CleanDCIM-release.jks" -alias clean-dcim -keyalg RSA -keysize 4096 -validity 10000
   ```

   If `keytool` is not on PATH, run it from your JDK's `bin` directory. Choose
   a strong password when prompted. Keep a secure backup of the keystore and
   password: future updates must use the same key. With the default PKCS12
   keystore, the key password is the same as the keystore password.
2. In GitHub, open the repository's **Settings > Secrets and variables >
   Actions > New repository secret** and add:

   - `RELEASE_KEYSTORE_BASE64`: the entire keystore encoded as Base64. On
     Windows, copy it without printing it using:

     ```powershell
     [Convert]::ToBase64String([IO.File]::ReadAllBytes("$HOME\CleanDCIM-release.jks")) | Set-Clipboard
     ```

     Paste the clipboard into the secret field, then clear the clipboard.
   - `RELEASE_KEYSTORE_PASSWORD`: the keystore password.
   - `RELEASE_KEY_ALIAS`: `clean-dcim` (or your chosen alias).
   - `RELEASE_KEY_PASSWORD`: the key password.

   These are repository secrets; the workflow does not select a GitHub
   environment. Do not commit the keystore or share these values. GitHub
   provides the workflow token automatically; no personal access token is
   required.
3. Commit and push the workflow and build changes. For each release, update
   `versionName` and increment `versionCode` in `app/build.gradle`, commit and
   push, then push a matching tag. For example, for version `1.5`:

   ```powershell
   git tag v1.5
   git push origin v1.5
   ```

   Follow the run in the **Actions** tab; when it succeeds, download the APK
   under **Releases > Assets**. A failed run can be retried from Actions.

The new release key differs from the debug key used by the supplied APKs.
Android will not install the new APK over those installations: uninstall the
old app first (this removes its app data), then install the release APK.
Subsequent releases signed with the same key can update it in place.

For local release builds, set `RELEASE_KEYSTORE_FILE`,
`RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`, and `RELEASE_KEY_PASSWORD` in
the environment before running `.\gradlew.bat assembleRelease`. Alternatively,
put `storeFile`, `storePassword`, `keyAlias`, and `keyPassword` in the
gitignored `keystore.properties` at the repository root. Escape backslashes
in the `storeFile` path as `\\`. Without signing configuration, a local release
APK is unsigned.

Physical Samsung/One UI validation is still required: verify permission
denial/retry, screenshots and screen recordings, Camera exclusion, collisions,
disable, screen-off watching, reboot, gallery refresh, denied/disabled
notifications, independent error-category settings, empty-folder cleanup,
recreation of Screenshots after cleanup, log expansion, live updates, clear,
restart persistence, and three-day expiry.
