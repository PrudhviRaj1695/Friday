# FRIDAY Windows Development Workflow

This keeps GitHub as the source of truth while enabling fast local iteration on Windows and live testing on a Galaxy S25 Ultra.

## Recommended workflow

- Edit and run locally in Android Studio.
- Keep the Galaxy S25 Ultra connected over USB as the primary development target.
- Use Compose Preview / Live Edit for supported Compose UI edits.
- Use Apply Changes for supported incremental Kotlin/resource edits.
- Use a full Run/redeploy for Gradle, manifest, dependency, native library, model-runtime and structural changes.
- Push commits to GitHub; GitHub Actions remains the clean-build and emulator-test gate.

## One-time Windows setup

1. Install the latest stable Android Studio: https://developer.android.com/studio
2. During Setup Wizard, install Android SDK Platform 35, Android SDK Build-Tools 35, Platform-Tools and Android Emulator.
3. Install Git for Windows: https://git-scm.com/download/win
4. Clone the repository in PowerShell:

```powershell
mkdir $HOME\source -Force
cd $HOME\source
git clone https://github.com/PrudhviRaj1695/Friday.git
cd Friday
```

5. Open the cloned folder in Android Studio and let Gradle sync.
6. On the Galaxy S25 Ultra: Settings > About phone > Software information > tap Build number seven times; enable Developer options > USB debugging.
7. Connect USB, unlock the phone, accept the RSA debugging prompt, then verify:

```powershell
adb devices
```

The device should show as `device`, not `unauthorized`.

## First install (only once, unless reinstall is required)

From the repository root:

```powershell
.\gradlew.bat installDebug
```

Launch FRIDAY on the phone and keep it open while iterating.

## Fast UI iteration

In Android Studio:

1. Open File > Settings > Editor > Live Edit.
2. Enable Live Edit; choose Automatic or Manual on Save.
3. Keep the app running on the S25 Ultra.
4. Edit existing @Composable function bodies. Supported changes should update live.
5. Use Compose Preview for isolated composables and different screen configurations.

Live Edit is experimental and has limitations. It is intended for Compose UI iteration, not a replacement for full builds.

## Incremental code changes

Use Android Studio's Apply Code Changes / Apply Changes and Restart Activity when supported. If the IDE says the change cannot be applied, use Run.

A full redeploy/restart is expected for changes such as:
- Gradle dependencies or plugins
- AndroidManifest.xml
- native .so libraries or inference runtime
- adding/removing fields or methods, changing signatures or class hierarchy
- model initialization and other structural changes

## Quick terminal commands

```powershell
# Pull remote changes before starting a new session
git pull --rebase

# Build without installing
.\gradlew.bat :app:assembleDebug

# Install/update on connected S25 Ultra
.\gradlew.bat :app:installDebug

# Run unit tests
.\gradlew.bat testDebugUnitTest

# Run connected Android instrumentation tests (emulator or device)
.\gradlew.bat connectedDebugAndroidTest

# Watch device logs
adb logcat

# Check connected devices
adb devices
```

## GitHub collaboration rule

If ChatGPT or another GitHub editor commits changes remotely while you have local edits:
1. Save or commit your local work.
2. Run `git pull --rebase`.
3. Resolve conflicts before continuing.
4. Do not overwrite local changes with a hard reset.

Prefer short-lived feature branches or ensure the working tree is clean before requesting remote edits to the same files.

## Device and emulator strategy for this laptop

The known laptop has 16 GB RAM. Use the S25 Ultra as the primary live device to avoid the extra memory cost of an emulator. Keep one lightweight API 35 emulator available for automated UI/instrumentation tests if the laptop handles it comfortably. GitHub Actions runs a clean Android APK build after commits; emulator smoke tests have been removed.

The emulator cannot validate S25 Ultra GPU/NPU acceleration, thermal throttling, battery impact or Kokoro performance. Those require the physical phone.

## Performance validation

Do not measure LLM speed using Compose Live Edit or a debug build alone. Record:
- model/backend selected (GPU or CPU)
- prompt/prefill latency
- time to first token
- decode tokens/second
- total generation time
- Kokoro synthesis time separately
- device temperature / thermal state and battery impact

Use the same fixed prompts and model files for repeatable comparisons. Run clean builds for performance comparisons.
