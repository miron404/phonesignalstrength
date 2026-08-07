# AI Context — MobiloSignal App

## Project overview
Android app (`barilyuk.mobilosignal`) that displays real-time cellular signal strength for dual-SIM phones. Shows operator name, dBm, network type (2G/3G/4G/5G), and a visual gradient bar for each SIM simultaneously. Also runs a foreground service with a notification showing signal info.

- **Language:** Java 8
- **Min SDK:** 26, **Target/Compile SDK:** 34
- **UI:** ConstraintLayout + MaterialComponents DayNight theme (auto dark/light)
- **Branch:** `dual-sim-redesign` (contains all recent changes)

## Key files
| File | Purpose |
|------|---------|
| `MainActivity.java` | Main UI: displays both SIMs with signal bars, operator names, controls |
| `SignalStrengthService.java` | Foreground service: notification with signal info |
| `activity_main.xml` | Layout: two SIM sections, each with gradient bar + marker |
| `BootReceiver.java` | Auto-start service on boot |
| `BitmapUtils.java` | Generates notification status bar icon from dBm number |
| `.github/workflows/main.yml` | CI: manual APK build (debug/release) |

## What was changed (this session)

### 1. Dual SIM redesign (commit `8f9a492`)
- **Layout:** two separate SIM sections, each with own gradient bar + marker + operator name
- **Removed** the SIM selection RadioGroup toggle — both SIMs shown simultaneously
- **Added** carrier/operator name display via `SubscriptionInfo.getCarrierName()`
- SIM2 section auto-hides when only one SIM is present
- Notification shows both SIMs

### 2. Dark theme fix
- All TextViews use `?android:attr/textColorPrimary` / `?android:attr/textColorSecondary`
- These auto-adapt to MaterialComponents DayNight (light/dark)

### 3. SIM2 disappearing bug fix (commit `d7e50ad`)
- **Root cause:** `startListening()` only ran once in `onCreate`. If SIM2 temporarily vanished from `getActiveSubscriptionInfoList()` (network switch, radio restart), it was lost forever until app data reset.
- **Fix:** Added `SubscriptionManager.OnSubscriptionsChangedListener` — auto re-detects SIMs on any subscription change
- **Fix:** `refreshSimDetection()` on `onResume` — re-checks SIMs when returning to app
- **Fix:** Track SIMs by `subscriptionId` instead of hardcoded `slotIndex` 0/1
- **Fix:** Removed preemptive `hideSim2Section()` in `onCreate`

### 4. CI workflow (`.github/workflows/main.yml`)
- Manual `workflow_dispatch` — builds debug or release APK
- Generates ephemeral JKS keystore (hex passwords, no special chars)
- Builds unsigned APK via Gradle, then signs with `apksigner` from Android SDK
- **Key lesson:** JDK 17 PKCS12 `keytool` has a `PBES2` padding bug causing `BadPaddingException`. **Use JKS format** and **sign with `apksigner` separately** (not Gradle's built-in signing).
- `--no-daemon` for Gradle in CI
- Paths use `rootProject.file()` for correct resolution from subproject

### 5. Other
- Removed deprecated `package` attribute from `AndroidManifest.xml` (namespace in build.gradle)
- Cleaned up unused imports and dead code
- Added `*.txt` to `.gitignore`

## Gotchas / notes for future work
- **Do not use PKCS12 keystores** with JDK 17+ in CI — use JKS
- **Do not use Gradle signing** in CI — sign with `apksigner` after build
- `OnSubscriptionsChangedListener` is NOT a functional interface — use anonymous class, not lambda
- SIM slot indices are unreliable; always track by `subscriptionId`
- `System.getenv()` in Gradle `signingConfigs` is unreliable on CI runners
- The app uses the built-in MaterialComponents DayNight theme, not custom themes from `styles.xml`
