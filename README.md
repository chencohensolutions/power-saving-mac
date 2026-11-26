# power-saving-mac

**BatteryTime**: a macOS menu bar app that shows battery time remaining and helps your Mac use less power. It's laid out like Apple's battery menu, with extra controls added.

## Features
- Battery time remaining in the menu bar, plus a menu laid out like Apple's battery menu (power source, Low Power Mode, apps using significant energy, Battery Settings…)
- **Stay on with lid closed**: keeps the Mac running on battery with the lid shut (`pmset disablesleep`)
- **Low Power Mode when lid closed**: turned on automatically while the lid is closed
- **Battery Saver**: Off / Light / Balanced / Max, active Never / only when the lid is closed / Always
  - Light: all your apps on efficiency cores
  - Balanced: Light, plus power-hungry apps capped at 50%
  - Max: Light, plus power-hungry apps paused
  - Per-app rules, a reason shown next to each limited app, a preview before limits start, "Pause for 1 hour", and a recap after each lid close
- **Control Center controls** (macOS 26+): Battery Time, Stay On With Lid Closed, Battery Saver, Low Power Mode When Lid Closed
- **Phone Companion**: see and control all of this from your Android phone while the lid is closed (menu → Phone Companion…, then the app in [companion/](companion/README.md))

## Install
Requires macOS 26+, Xcode, and an Apple ID added in Xcode → Settings → Accounts.

```
bash install.sh
```

The script generates the Xcode project with [XcodeGen](https://github.com/yonaskolb/XcodeGen) (installing it through Homebrew if needed), then builds the app and installs it to /Applications. It also adds a sudoers rule that allows only the four `pmset` commands the app uses, so the toggles don't ask for a password.

Then:
- Hide Apple's battery icon: System Settings → Menu Bar → Battery → off
- Add the controls: Control Center → Edit Controls → search "BatteryTime"

## How limiting works
- **E-cores**: Darwin background policy (`setpriority(PRIO_DARWIN_PROCESS, pid, PRIO_DARWIN_BG)`). This also lowers the app's disk and network priority.
- **50% / 25% / Pause**: SIGSTOP/SIGCONT duty cycling in 10 ms slices
- Only your own processes are touched. Core UI processes (WindowServer, Dock, Finder…) are excluded. All limits are removed when you open the lid, quit the app or log out, and on the next launch after a crash.

## Uninstall
```
pkill BatteryTime; rm -rf /Applications/BatteryTime.app; sudo rm /etc/sudoers.d/batterytime
```
