#!/bin/bash
# Builds BatteryTime (menu bar app + Control Center controls) and installs it.
# Run from this folder:  bash install.sh
set -e
cd "$(dirname "$0")"

# 1. Xcode
if ! xcodebuild -version >/dev/null 2>&1; then
  echo "❌ Full Xcode is needed. Install it from the App Store, open it once, then run:"
  echo "   sudo xcode-select -s /Applications/Xcode.app"
  exit 1
fi

# 2. XcodeGen (turns project.yml into an Xcode project)
if ! command -v xcodegen >/dev/null 2>&1; then
  if command -v brew >/dev/null 2>&1; then
    echo "Installing XcodeGen…"
    brew install xcodegen
  else
    echo "❌ Homebrew not found. Install XcodeGen (https://github.com/yonaskolb/XcodeGen) and run again."
    exit 1
  fi
fi

# 3. Your signing team (Apple ID added in Xcode → Settings → Accounts)
if [ -z "$TEAM_ID" ]; then
  TEAM_ID=$(security find-certificate -a -c "Apple Development" -p 2>/dev/null \
    | openssl x509 -noout -subject 2>/dev/null \
    | sed -n 's/.*OU *= *\([A-Z0-9]\{10\}\).*/\1/p' | head -1)
fi
if [ -z "$TEAM_ID" ]; then
  echo "❌ No Apple Development certificate found."
  echo "   Open Xcode → Settings → Accounts → add your Apple ID (free is fine),"
  echo "   then Manage Certificates → + → Apple Development. Run this again."
  echo "   (Or run: TEAM_ID=YOURTEAMID bash install.sh)"
  exit 1
fi
echo "Signing with team $TEAM_ID"
export TEAM_ID

# 4. Generate + build
xcodegen generate --quiet
BUILD="$(pwd)/build"
xcodebuild -project BatteryTime.xcodeproj -target BatteryTime -configuration Release \
  SYMROOT="$BUILD" -allowProvisioningUpdates -quiet build

# 5. Install
APP="/Applications/BatteryTime.app"
pkill -x BatteryTime 2>/dev/null || true
sleep 1
rm -rf "$APP"
cp -R "$BUILD/Release/BatteryTime.app" "$APP"

# 6. One-time permission: run ONLY these pmset commands without a password
echo "Setting up passwordless toggles — enter your Mac password once:"
TMP=$(mktemp)
echo "$(whoami) ALL=(root) NOPASSWD: /usr/bin/pmset -a disablesleep 0, /usr/bin/pmset -a disablesleep 1, /usr/bin/pmset -a lowpowermode 0, /usr/bin/pmset -a lowpowermode 1" > "$TMP"
if sudo visudo -cf "$TMP" >/dev/null; then
  sudo install -m 0440 -o root -g wheel "$TMP" /etc/sudoers.d/batterytime
fi
rm -f "$TMP"

# 7. Start at login + launch (launching registers the Control Center controls)
osascript -e 'tell application "System Events" to delete (every login item whose name is "BatteryTime")' 2>/dev/null || true
osascript -e "tell application \"System Events\" to make login item at end with properties {path:\"$APP\", hidden:true}" >/dev/null 2>&1 || true
open "$APP"

echo ""
echo "✅ BatteryTime installed."
echo "   Hide Apple's battery icon: System Settings → Menu Bar → Battery → off"
echo "   Add the controls: click Control Center → Edit Controls → search \"BatteryTime\""
