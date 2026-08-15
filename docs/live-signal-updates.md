# Live signal updates: what works, what does not, and what it costs

Notes from investigating why a reading can sit on the same value (e.g. a steady
`-83 dBm`) while another app shows it drifting between `-81` and `-83`.

## Why the reading looks frozen

There is no rounding, quantisation or smoothing anywhere in this app. The value
goes from the modem to the screen as an integer, and the only filtering is
de-duplication of *identical* consecutive readings (in `SignalRepository`, in
the `StateFlow`, in `SignalBarView` and in the notification).

The app is event driven: it sleeps until `TelephonyCallback.SignalStrengthsListener`
fires. Android applies **hysteresis** to those reports, so the callback does not
fire for a change of a couple of dB. This is the same mechanism the system status
bar uses, and it is the cheapest thing to do.

Two other things make a specific SIM look frozen:

- **It is not the data SIM.** The modem keeps the non-data SIM in a low power
  monitoring mode and reports its signal far less often.
- **You are comparing different measurements.** On 5G NSA this app shows NR
  SS-RSRP; an app showing "4G" is showing the LTE anchor's RSRP. Different
  numbers, both correct.

## The three mechanisms

| Mechanism | Works? | Cost |
|---|---|---|
| `TelephonyManager.getSignalStrength()` on a timer | **No** | Battery, for nothing |
| `getAllCellInfo()` / `requestCellInfoUpdate()` on a timer | **Yes** | `ACCESS_FINE_LOCATION` |
| `setSignalStrengthUpdateRequest()` | Probably | Fragile, vendor dependent |

### 1. Polling `getSignalStrength()` — tried, reverted

Implemented in commit `9b0853d`, reverted in `05004cb` after testing on a real
device: the readings did not come alive.

`getSignalStrength()` (API 28+) returns the modem's **last reported** measurement
from the framework cache. If the modem only reports on hysteresis crossings,
polling reads the same stale value more often. Pure cost, no benefit.

Do not try this again.

### 2. Polling cell info — works, needs location

This is what [Atalaya](https://github.com/naval-cat/atalaya) does. It polls
`getCells()` once a second through the NetMonster Core library, which wraps
`TelephonyManager.getAllCellInfo()` and `requestCellInfoUpdate()`.

The difference from mechanism 1 is the whole point: `requestCellInfoUpdate()`
(API 29+) **forces the modem to take a fresh measurement** rather than returning
a cache. That is why its numbers move.

The catch: `getAllCellInfo()` and `requestCellInfoUpdate()` both require
**`ACCESS_FINE_LOCATION`**. Atalaya's manifest declares `ACCESS_FINE_LOCATION`,
`ACCESS_COARSE_LOCATION` and `READ_PHONE_STATE`, and it asks for location before
it will run at all.

This app deliberately does not request location (see the comment in
`AndroidManifest.xml`): signal strength itself needs no permission, and location
is only required by `getAllCellInfo()`, which this app does not call. Dropping it
was a deliberate privacy decision, and the app currently ships five permissions,
none of them about where the user is.

**If this is ever implemented**, the design should be:

- Request `ACCESS_FINE_LOCATION` *only* when the user switches the live mode on,
  never at startup. The rest of the app must keep working when it is refused.
- Do not add NetMonster Core. Only the signal strength is needed, not cell
  identity or multi-source aggregation, so `requestCellInfoUpdate()` used
  directly is enough and adds no dependency.
- Poll inside `SignalRepository` next to the existing callbacks, so it starts and
  stops with the same reference counted lifecycle.
- Note that the user also needs location *services* switched on system-wide, not
  just the permission granted — worth saying in the UI, since it fails silently
  otherwise.

The reverted commit `9b0853d` already contains the settings UI (a `ListPreference`
with a rate in seconds), the preference plumbing and the poll loop shape. It can
be cherry-picked as a starting point; only the "how do we read" part needs
replacing.

### 3. `setSignalStrengthUpdateRequest()` — not attempted

`TelephonyManager.setSignalStrengthUpdateRequest()` (API 30+) is the API actually
designed for asking the modem to report more finely. It stays event driven, and
it needs no location permission.

It was not attempted because it requires building a `SignalThresholdInfo` for
each radio access network and each measurement type, it throws
`IllegalArgumentException` on malformed threshold arrays, and how much it helps
depends on the modem vendor. Writing that without a device to test against is
how you ship fragile code.

If mechanism 2 is ever rejected on privacy grounds but live updates are still
wanted, this is the one to explore — with a device in hand.

## API levels, verified against the SDK

Checked in `platforms/android-35/data/api-versions.xml`, not from memory:

| Method | Since |
|---|---|
| `CellSignalStrengthLte.getRsrp/getRsrq/getRssnr` | 26 |
| `CellSignalStrengthGsm.getBitErrorRate` | 29 |
| `CellSignalStrengthNr.getSsRsrq/getSsSinr` | 29 |
| `SignalStrength.getCellSignalStrengths` | 29 |
| `TelephonyManager.getSignalStrength` | 28 |
| `CellSignalStrengthWcdma.getEcNo` | 30 |
| `CellSignalStrengthGsm.getRssi` | 30 |
