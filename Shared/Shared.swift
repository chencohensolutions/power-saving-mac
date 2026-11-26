import Foundation

/// Code shared by the app and the Control Center extension.
/// They talk through App Group defaults + a Darwin notification.
enum Shared {
    static let groupID = (Bundle.main.object(forInfoDictionaryKey: "AppGroup") as? String)
        ?? "com.hencohen.batterytime"
    static let defaults = UserDefaults(suiteName: groupID) ?? .standard
    static let commandNote = "com.hencohen.batterytime.command"

    enum Key {
        // Settings (written by the app, read by both)
        static let preset = "preset"
        static let lastPreset = "lastPreset"
        static let ecoLid = "ecoWhenLidClosed"
        // State published by the app for the controls
        static let sleepDisabled = "state.sleepDisabled"
        static let batteryTitle = "state.batteryTitle"
        static let batterySymbol = "state.batterySymbol"
        // Commands sent by the controls to the app
        static let cmdStayAwake = "cmd.stayAwake"
        static let cmdPreset = "cmd.preset"
        static let cmdEcoLid = "cmd.ecoLid"
        static let cmdShowMenu = "cmd.showMenu"
    }

    enum Kind {
        static let status = "com.hencohen.batterytime.control.status"
        static let stayAwake = "com.hencohen.batterytime.control.stayAwake"
        static let saver = "com.hencohen.batterytime.control.saver"
        static let ecoLid = "com.hencohen.batterytime.control.ecoLid"
    }

    /// Store a command and wake the app up
    static func command(_ key: String, _ value: Any) {
        defaults.set(value, forKey: key)
        CFNotificationCenterPostNotification(CFNotificationCenterGetDarwinNotifyCenter(),
                                             CFNotificationName(commandNote as CFString), nil, nil, true)
    }
}

enum Preset: String {
    case off, light, balanced, max
    var title: String {
        switch self {
        case .off: return "Off"
        case .light: return "Light"
        case .balanced: return "Balanced"
        case .max: return "Max"
        }
    }
    var detail: String {
        switch self {
        case .off: return "Only your app rules"
        case .light: return "All apps on efficiency cores"
        case .balanced: return "Light, plus power-hungry apps capped at 50%"
        case .max: return "Light, plus power-hungry apps paused"
        }
    }
}
