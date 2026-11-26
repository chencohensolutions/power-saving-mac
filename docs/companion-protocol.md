# Phone Companion protocol (v1)

BatteryTime on the Mac (`App/CompanionServer.swift`) serves a small JSON API on the local network so the phone app
(`companion/`) can see and control it while the lid is closed. The phone's side is
`companion/sharedLogic/.../CompanionClient.kt`.

## Finding the Mac

- TCP port **47070**, plain HTTP/1.1, one request per connection (`Connection: close`).
- Advertised over Bonjour as `_batterytime._tcp`, with the Mac's name (System Settings → General → Sharing) as the
  service name. The phone uses it to find the Mac again when its IP address changes.
- Off until the user turns on **Allow phone control** in BatteryTime's menu → Phone Companion….

## Pairing

The Phone Companion window shows a QR code with this link, and the same address and code as text:

```
batterytime://pair?h=<IPv4>&p=47070&c=<CODE>&n=<Mac name, percent-encoded>
```

`CODE` is 8 characters from `ABCDEFGHJKLMNPQRSTUVWXYZ23456789` (shown as `ABCD-EFGH`). Every request except
`/v1/hello` sends it as `Authorization: Bearer <CODE>` (dashes and case are ignored). **New Code** on the Mac makes a
new one, which unpairs every phone. After 10 wrong codes in a minute the Mac refuses every request for the rest of
that minute.

## Endpoints

| Request | Answer |
| --- | --- |
| `GET /v1/hello` (no code) | `{"app":"BatteryTime","v":1,"name":"<Mac name>"}` |
| `GET /v1/state` | the state below |
| `POST /v1/command` with a command as JSON | the state after the command, or 400 `{"error": "..."}` |
| `GET /v1/icon?app=<name>` | a 64×64 PNG of a running app's icon, or 404 |

A wrong or missing code is `401 {"error":"Wrong pairing code"}`.

### State

```json
{
  "v": 1,
  "mac": {"name": "MacBook Pro"},
  "battery": {"percent": 76, "charging": false, "onAC": false, "minutes": 312,
              "timeText": "5:12 Remaining", "powerSource": "Battery"},
  "lid": {"closed": true, "closedSince": 1795000000000},
  "stayAwake": true,
  "lowPowerMode": true,
  "lowPowerWhenLidClosed": true,
  "saver": {"preset": "balanced", "activeMode": "lidClosed", "active": true, "pausedUntil": null,
            "limitedCount": 5, "status": "Saving power · 5 apps limited",
            "statusDetail": "Because the lid is closed", "otherAppsOnECores": 3},
  "apps": [{"name": "Google Chrome", "power": 42.3, "action": "p50", "reason": "using 42",
            "rule": null, "limitedNow": true}],
  "rules": {"Music": "none"},
  "runningApps": ["Google Chrome", "Music", "Slack"],
  "lastRecap": "1h 20m · used 9% battery · 4 apps limited",
  "time": 1795000600000
}
```

Times are milliseconds since 1970. `minutes` is time to empty on battery and to full while charging, `null` while
macOS is still calculating. `preset` is `off|light|balanced|max`, `activeMode` is `never|lidClosed|always`, and an
app's `action`/`rule` is `ecores|p50|p25|pause|none`. Readers ignore fields they don't know.

### Commands

```json
{"type": "setStayAwake", "on": true}
{"type": "setLowPowerMode", "on": true}
{"type": "setLowPowerWhenLidClosed", "on": true}
{"type": "setPreset", "preset": "balanced"}
{"type": "setActiveMode", "mode": "lidClosed"}
{"type": "pauseLimits", "minutes": 60}
{"type": "resumeLimits"}
{"type": "setAppRule", "app": "Slack", "action": "p50"}    // "auto": follow Battery Saver again
{"type": "refresh"}                                       // re-measure energy use now
```

## Security

The code travels in plain HTTP on the local network, so anyone on the same Wi-Fi who can read its traffic could
learn it. It only controls BatteryTime's own settings (sleep, Low Power Mode, Battery Saver), not the rest of the
Mac. Use **New Code** to revoke, and turn Phone Companion off on networks you don't trust.
