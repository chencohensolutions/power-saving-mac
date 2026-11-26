# BatteryTime Companion (Android)

A phone screen for BatteryTime, for when the Mac's lid is closed and its menu bar can't be reached: battery and time
left, Stay On With Lid Closed, Low Power Mode, Battery Saver (level, when it's active, pause), the apps using
energy and what's done with each, and per-app rules. It talks to the Mac over the local network
([docs/companion-protocol.md](../docs/companion-protocol.md)).

Kotlin Multiplatform with native UI, like the other KMP projects:

- `sharedLogic/` — everything but the UI, shared: `MacState` (the Mac's state), `Command`, `Pairing` (the QR link
  and typed address/code), `CompanionClient` (Ktor: OkHttp on Android, Darwin on iOS), `DashboardController`
  (polling every 2 s while visible, taps shown at once and undone if they fail, finding the Mac again when its
  address changes) and `Format` (the text both UIs show). Tests in `commonTest`.
- `androidApp/` — Jetpack Compose, Material 3 (the phone's own colors). `PairScreen` scans the Mac's QR code with
  Google's code scanner (no camera permission) or takes the address and code by hand, listing the Macs it finds on
  the Wi-Fi. `DashboardScreen` is the Mac's menu. `NsdMacFinder` finds Macs over Bonjour (`_batterytime._tcp`).
- iOS: the shared logic builds for it; a SwiftUI app can be added next to `androidApp`.

## Use it

1. On the Mac: BatteryTime menu → **Phone Companion…** → turn on **Allow phone control**. Allow BatteryTime on the
   local network if macOS asks.
2. On the phone: install the app, tap **Scan the Mac's code**. (Scanning the QR with the camera app also opens it.)
3. Close the lid with **Stay On With Lid Closed** on. The phone keeps working as long as both are on the same Wi-Fi.

## Build

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
./gradlew :sharedLogic:testAndroidHostTest     # shared tests on the JVM
./gradlew :androidApp:installDebug             # build and install on the connected phone
```
