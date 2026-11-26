import WidgetKit
import SwiftUI
import AppIntents

// Control Center / menu bar controls for BatteryTime.
// The controls never do the work themselves (the extension is sandboxed):
// they send a command to the BatteryTime app, which applies it.

@main
struct BatteryTimeControls: WidgetBundle {
    var body: some Widget {
        BatteryStatusControl()
        StayAwakeControl()
        BatterySaverControl()
        LidLowPowerControl()
    }
}

// MARK: - Battery time (button that opens the BatteryTime menu)

struct StatusValue: Sendable {
    var title: String
    var symbol: String
}

struct StatusProvider: ControlValueProvider {
    var previewValue: StatusValue { StatusValue(title: "3:42 Left", symbol: "battery.75percent") }
    func currentValue() async throws -> StatusValue {
        let d = Shared.defaults
        return StatusValue(title: d.string(forKey: Shared.Key.batteryTitle) ?? "Battery",
                           symbol: d.string(forKey: Shared.Key.batterySymbol) ?? "battery.100percent")
    }
}

struct ShowMenuIntent: AppIntent {
    static let title: LocalizedStringResource = "Show BatteryTime Menu"
    func perform() async throws -> some IntentResult {
        Shared.command(Shared.Key.cmdShowMenu, true)
        return .result()
    }
}

struct BatteryStatusControl: ControlWidget {
    var body: some ControlWidgetConfiguration {
        StaticControlConfiguration(kind: Shared.Kind.status, provider: StatusProvider()) { value in
            ControlWidgetButton(action: ShowMenuIntent()) {
                Label(value.title, systemImage: value.symbol)
            }
        }
        .displayName("Battery Time")
        .description("Time remaining on battery. Click to open BatteryTime.")
    }
}

// MARK: - Stay on with lid closed

struct FlagProvider: ControlValueProvider {
    let key: String
    var previewValue: Bool { false }
    func currentValue() async throws -> Bool { Shared.defaults.bool(forKey: key) }
}

struct SetStayAwakeIntent: SetValueIntent {
    static let title: LocalizedStringResource = "Stay On With Lid Closed"
    @Parameter(title: "Stay on") var value: Bool
    func perform() async throws -> some IntentResult {
        Shared.defaults.set(value, forKey: Shared.Key.sleepDisabled)   // show it right away
        Shared.command(Shared.Key.cmdStayAwake, value)
        return .result()
    }
}

struct StayAwakeControl: ControlWidget {
    var body: some ControlWidgetConfiguration {
        StaticControlConfiguration(kind: Shared.Kind.stayAwake,
                                   provider: FlagProvider(key: Shared.Key.sleepDisabled)) { isOn in
            ControlWidgetToggle("Stay On, Lid Closed", isOn: isOn, action: SetStayAwakeIntent()) { on in
                Label(on ? "On" : "Off", systemImage: on ? "cup.and.saucer.fill" : "cup.and.saucer")
            }
        }
        .displayName("Stay On With Lid Closed")
        .description("Keep your Mac running when you close the lid.")
    }
}

// MARK: - Battery Saver (on/off, remembers your level)

struct SaverValue: Sendable {
    var on: Bool
    var title: String
}

struct SaverProvider: ControlValueProvider {
    var previewValue: SaverValue { SaverValue(on: true, title: "Balanced") }
    func currentValue() async throws -> SaverValue {
        let p = Preset(rawValue: Shared.defaults.string(forKey: Shared.Key.preset) ?? "") ?? .balanced
        return SaverValue(on: p != .off, title: p.title)
    }
}

struct SetSaverIntent: SetValueIntent {
    static let title: LocalizedStringResource = "Battery Saver"
    @Parameter(title: "On") var value: Bool
    func perform() async throws -> some IntentResult {
        let d = Shared.defaults
        let last = d.string(forKey: Shared.Key.lastPreset) ?? Preset.balanced.rawValue
        let new = value ? last : Preset.off.rawValue
        d.set(new, forKey: Shared.Key.preset)
        Shared.command(Shared.Key.cmdPreset, new)
        return .result()
    }
}

struct BatterySaverControl: ControlWidget {
    var body: some ControlWidgetConfiguration {
        StaticControlConfiguration(kind: Shared.Kind.saver, provider: SaverProvider()) { value in
            ControlWidgetToggle("Battery Saver", isOn: value.on, action: SetSaverIntent()) { on in
                Label(on ? value.title : "Off", systemImage: on ? "leaf.fill" : "leaf")
            }
        }
        .displayName("Battery Saver")
        .description("Limit power-hungry apps (BatteryTime).")
    }
}

// MARK: - Low Power Mode when lid closed

struct SetEcoLidIntent: SetValueIntent {
    static let title: LocalizedStringResource = "Low Power Mode When Lid Closed"
    @Parameter(title: "On") var value: Bool
    func perform() async throws -> some IntentResult {
        Shared.defaults.set(value, forKey: Shared.Key.ecoLid)
        Shared.command(Shared.Key.cmdEcoLid, value)
        return .result()
    }
}

struct LidLowPowerControl: ControlWidget {
    var body: some ControlWidgetConfiguration {
        StaticControlConfiguration(kind: Shared.Kind.ecoLid,
                                   provider: FlagProvider(key: Shared.Key.ecoLid)) { isOn in
            ControlWidgetToggle("Low Power, Lid Closed", isOn: isOn, action: SetEcoLidIntent()) { on in
                Label(on ? "On" : "Off", systemImage: on ? "tortoise.fill" : "tortoise")
            }
        }
        .displayName("Low Power Mode When Lid Closed")
        .description("Turn on Low Power Mode while the lid is closed.")
    }
}
