import Cocoa
import IOKit.ps
import Darwin
import WidgetKit

// MARK: - Model

enum LimitAction: String {
    case none, ecores, p50, p25, pause
    var title: String {
        switch self {
        case .none: return "Never limit"
        case .ecores: return "Efficiency cores"
        case .p50: return "Limit to 50%"
        case .p25: return "Limit to 25%"
        case .pause: return "Pause"
        }
    }
    var tag: String {
        switch self {
        case .none: return "Never"
        case .ecores: return "E-cores"
        case .p50: return "50%"
        case .p25: return "25%"
        case .pause: return "Paused"
        }
    }
    var duty: Int? {
        switch self {
        case .p50: return 50
        case .p25: return 25
        case .pause: return 0
        default: return nil
        }
    }
    var strength: Int {
        switch self {
        case .none: return 0
        case .ecores: return 1
        case .p50: return 2
        case .p25: return 3
        case .pause: return 4
        }
    }
}

enum ActiveMode: String {
    case never, lidClosed, always
    var title: String {
        switch self {
        case .never: return "Never"
        case .lidClosed: return "Only when lid is closed"
        case .always: return "Always"
        }
    }
}

extension Preset {
    var allECores: Bool { self != .off }
    var hogAction: LimitAction? {
        switch self {
        case .balanced: return .p50
        case .max: return .pause
        default: return nil
        }
    }
}

let hogThreshold = 30.0   // energy impact that counts as "power-hungry"

// MARK: - Process helpers

let systemNames: Set<String> = [
    "kernel_task", "launchd", "WindowServer", "loginwindow", "Dock", "Finder",
    "SystemUIServer", "ControlCenter", "NotificationCenter", "coreaudiod",
    "top", "BatteryTime", "Terminal", "iTerm2", "sudo", "Spotlight"
]

func bsdInfo(_ pid: pid_t) -> proc_bsdinfo? {
    var info = proc_bsdinfo()
    let size = Int32(MemoryLayout<proc_bsdinfo>.size)
    return proc_pidinfo(pid, PROC_PIDTBSDINFO, 0, &info, size) == size ? info : nil
}

func parentPid(_ pid: pid_t) -> pid_t { bsdInfo(pid).map { pid_t($0.pbi_ppid) } ?? 0 }

/// pid → parent pid, for every process you own (system/root ones excluded)
func myProcesses() -> [pid_t: pid_t] {
    let count = proc_listallpids(nil, 0)
    guard count > 0 else { return [:] }
    var buf = [pid_t](repeating: 0, count: Int(count) + 64)
    let got = Int(proc_listallpids(&buf, Int32(buf.count * MemoryLayout<pid_t>.size)))
    let me = getpid(), uid = getuid()
    var out: [pid_t: pid_t] = [:]
    for pid in buf.prefix(Swift.max(0, got)) where pid > 1 && pid != me {
        guard let info = bsdInfo(pid), info.pbi_uid == uid else { continue }
        let bytes = Mirror(reflecting: info.pbi_comm).children.compactMap { $0.value as? CChar }
        let name = String(decoding: bytes.prefix(while: { $0 != 0 }).map { UInt8(bitPattern: $0) }, as: UTF8.self)
        if systemNames.contains(name) { continue }
        out[pid] = pid_t(info.pbi_ppid)
    }
    return out
}

struct AppGroup {
    let name: String
    let icon: NSImage?
    var pids: [pid_t]
    var power: Double
    var peak: Double = 0
}

/// Samples `top` (energy impact) and groups helper processes under their app
func sampleGroups() -> [AppGroup] {
    let p = Process()
    p.executableURL = URL(fileURLWithPath: "/usr/bin/top")
    p.arguments = ["-l", "2", "-s", "1", "-o", "power", "-n", "60", "-stats", "pid,cpu,power,command"]
    let pipe = Pipe()
    p.standardOutput = pipe
    p.standardError = Pipe()
    guard (try? p.run()) != nil else { return [] }
    let data = pipe.fileHandleForReading.readDataToEndOfFile()
    p.waitUntilExit()
    let lines = (String(data: data, encoding: .utf8) ?? "").components(separatedBy: "\n")
    guard let hdr = lines.lastIndex(where: { $0.trimmingCharacters(in: .whitespaces).hasPrefix("PID") })
    else { return [] }

    let me = getpid()
    var byName: [String: AppGroup] = [:]
    for line in lines[(hdr + 1)...] {
        let t = line.split(separator: " ", omittingEmptySubsequences: true)
        guard t.count >= 4,
              let pid = Int32(t[0].filter { $0.isNumber }),
              let power = Double(t[2]), power > 0.3 else { continue }
        var name = t[3...].joined(separator: " ")
        if pid <= 1 || pid == me || systemNames.contains(name) || kill(pid, 0) != 0 { continue }
        var icon: NSImage? = nil
        var cur = pid
        for _ in 0..<6 {   // walk up to the owning app (Chrome Helper → Chrome)
            if let app = NSRunningApplication(processIdentifier: cur), app.activationPolicy == .regular {
                name = app.localizedName ?? name
                icon = app.icon
                break
            }
            cur = parentPid(cur)
            if cur <= 1 { break }
        }
        if systemNames.contains(name) { continue }
        var g = byName[name] ?? AppGroup(name: name, icon: icon, pids: [], power: 0)
        g.pids.append(pid)
        g.power += power
        byName[name] = g
    }
    return byName.values.sorted { $0.power > $1.power }
}

// MARK: - Limiter (E-cores via Darwin background policy, % via SIGSTOP/SIGCONT)

final class Limiter {
    private let lock = NSLock()
    private var duty: [pid_t: Int] = [:]
    private var stopped = Set<pid_t>()
    private var background = Set<pid_t>()

    init() {
        for n in UserDefaults.standard.array(forKey: "limitedPids") as? [Int] ?? [] {
            kill(pid_t(n), SIGCONT)
            Limiter.setBackground(pid_t(n), false)
        }
        UserDefaults.standard.removeObject(forKey: "limitedPids")
        let t = Thread { [weak self] in self?.loop() }
        t.qualityOfService = .userInitiated
        t.start()
    }

    static func setBackground(_ pid: pid_t, _ on: Bool) {
        // PRIO_DARWIN_PROCESS = 4, PRIO_DARWIN_BG = 0x1000 → efficiency cores
        _ = setpriority(4, id_t(pid), on ? 0x1000 : 0)
    }

    private func loop() {
        var step = 0
        while true {
            lock.lock()
            let idle = duty.isEmpty
            for (pid, pct) in duty {
                let run = step * 10 < pct
                if run, stopped.contains(pid) { kill(pid, SIGCONT); stopped.remove(pid) }
                else if !run, !stopped.contains(pid) { kill(pid, SIGSTOP); stopped.insert(pid) }
            }
            lock.unlock()
            step = (step + 1) % 10
            usleep(idle ? 250_000 : 10_000)
        }
    }

    func apply(_ target: [pid_t: LimitAction]) {
        lock.lock()
        var newDuty: [pid_t: Int] = [:]
        for (pid, a) in target { if let d = a.duty { newDuty[pid] = d } }
        for pid in duty.keys where newDuty[pid] == nil {
            if stopped.remove(pid) != nil { kill(pid, SIGCONT) }
        }
        duty = newDuty
        let newBg = Set(target.filter { $0.value == .ecores }.map { $0.key })
        for pid in background.subtracting(newBg) { Limiter.setBackground(pid, false) }
        for pid in newBg.subtracting(background) { Limiter.setBackground(pid, true) }
        background = newBg
        let all = Set(duty.keys).union(background).map { Int($0) }
        lock.unlock()
        UserDefaults.standard.set(all, forKey: "limitedPids")
    }
}

struct Planned {
    let name: String
    let icon: NSImage?
    let action: LimitAction
    let reason: String
    let pids: [pid_t]
}

// MARK: - App

final class AppDelegate: NSObject, NSApplicationDelegate, NSMenuDelegate {
    private var statusItem: NSStatusItem!
    private var timers: [Timer] = []
    private var source: CFRunLoopSource?
    private var termSource: DispatchSourceSignal?
    private let limiter = Limiter()
    private let defaults = Shared.defaults

    // ---- Settings ----
    private var preset: Preset {
        get { Preset(rawValue: defaults.string(forKey: "preset") ?? "") ?? .balanced }
        set {
            defaults.set(newValue.rawValue, forKey: Shared.Key.preset)
            if newValue != .off { defaults.set(newValue.rawValue, forKey: Shared.Key.lastPreset) }
        }
    }
    private var activeMode: ActiveMode {
        get { ActiveMode(rawValue: defaults.string(forKey: "activeMode") ?? "") ?? .lidClosed }
        set { defaults.set(newValue.rawValue, forKey: "activeMode") }
    }
    private var rules: [String: LimitAction] {
        get { (defaults.dictionary(forKey: "appRules") as? [String: String] ?? [:])
                .compactMapValues { LimitAction(rawValue: $0) } }
        set { defaults.set(newValue.mapValues { $0.rawValue }, forKey: "appRules") }
    }
    private var ecoEnabled: Bool {
        get { defaults.bool(forKey: "ecoWhenLidClosed") }
        set { defaults.set(newValue, forKey: "ecoWhenLidClosed") }
    }

    // ---- State ----
    private var sleepDisabled = false
    private var lidClosed = false
    private var ecoTurnedOnByUs = false
    private var pauseUntil: Date?
    private var groups: [AppGroup] = []
    private var tracked: [String: AppGroup] = [:]   // apps limited while active (sticky)
    private var plan: [Planned] = []                // named apps: limited now, or preview
    private var otherCount = 0                      // other apps sent to E-cores
    private var sampling = false
    private let sampleQueue = DispatchQueue(label: "batterytime.sampler")
    private var batteryText = ""
    private var lastPct = 0
    private var lidClosedAt: Date?
    private var pctAtClose = 0
    private var maxLimitedWhileClosed = 0

    // MARK: Launch

    func applicationDidFinishLaunching(_ notification: Notification) {
        statusItem = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
        statusItem.button?.font = NSFont.monospacedDigitSystemFont(ofSize: 13, weight: .regular)
        let menu = NSMenu()
        menu.delegate = self
        menu.autoenablesItems = false
        statusItem.menu = menu

        let ctx = Unmanaged.passUnretained(self).toOpaque()
        if let src = IOPSNotificationCreateRunLoopSource({ ctx in
            guard let ctx = ctx else { return }
            Unmanaged<AppDelegate>.fromOpaque(ctx).takeUnretainedValue().refresh()
        }, ctx)?.takeRetainedValue() {
            source = src
            CFRunLoopAddSource(CFRunLoopGetMain(), src, .defaultMode)
        }

        signal(SIGTERM, SIG_IGN)
        let ts = DispatchSource.makeSignalSource(signal: SIGTERM, queue: .main)
        ts.setEventHandler { [weak self] in self?.releaseEverything(); exit(0) }
        ts.resume()
        termSource = ts

        timers.append(Timer.scheduledTimer(withTimeInterval: 30, repeats: true) { [weak self] _ in self?.refresh() })
        timers.append(Timer.scheduledTimer(withTimeInterval: 3, repeats: true) { [weak self] _ in self?.checkLid() })
        timers.append(Timer.scheduledTimer(withTimeInterval: 10, repeats: true) { [weak self] _ in self?.sample() })
        let me = Unmanaged.passUnretained(self).toOpaque()
        CFNotificationCenterAddObserver(CFNotificationCenterGetDarwinNotifyCenter(), me, { _, observer, _, _, _ in
            guard let observer = observer else { return }
            let app = Unmanaged<AppDelegate>.fromOpaque(observer).takeUnretainedValue()
            DispatchQueue.main.async { app.handleCommands() }
        }, Shared.commandNote as CFString, nil, .deliverImmediately)

        lidClosed = isLidClosed()
        refresh()
        sample()
        handleCommands()
    }

    /// Commands from the Control Center controls
    func handleCommands() {
        let d = defaults
        if let v = d.object(forKey: Shared.Key.cmdStayAwake) as? Bool {
            d.removeObject(forKey: Shared.Key.cmdStayAwake)
            setStayAwake(v)
        }
        if let raw = d.string(forKey: Shared.Key.cmdPreset) {
            d.removeObject(forKey: Shared.Key.cmdPreset)
            if let p = Preset(rawValue: raw) { preset = p; tracked = [:]; computeAndApply() }
        }
        if let v = d.object(forKey: Shared.Key.cmdEcoLid) as? Bool {
            d.removeObject(forKey: Shared.Key.cmdEcoLid)
            if v != ecoEnabled { toggleEco() }
        }
        if d.bool(forKey: Shared.Key.cmdShowMenu) {
            d.removeObject(forKey: Shared.Key.cmdShowMenu)
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) { self.statusItem.button?.performClick(nil) }
        }
    }

    func applicationWillTerminate(_ notification: Notification) { releaseEverything() }

    private func releaseEverything() {
        limiter.apply([:])
        if ecoTurnedOnByUs { setLowPower(false) }
    }

    // MARK: pmset helpers

    @discardableResult
    private func sudoPmset(_ args: [String]) -> Bool {
        let p = Process()
        p.executableURL = URL(fileURLWithPath: "/usr/bin/sudo")
        p.arguments = ["-n", "/usr/bin/pmset", "-a"] + args
        p.standardError = Pipe()
        guard (try? p.run()) != nil else { return false }
        p.waitUntilExit()
        return p.terminationStatus == 0
    }

    private func readSleepDisabled() -> Bool {
        let p = Process()
        p.executableURL = URL(fileURLWithPath: "/usr/bin/pmset")
        p.arguments = ["-g"]
        let pipe = Pipe()
        p.standardOutput = pipe
        guard (try? p.run()) != nil else { return false }
        let data = pipe.fileHandleForReading.readDataToEndOfFile()
        p.waitUntilExit()
        return (String(data: data, encoding: .utf8) ?? "").split(separator: "\n").contains { line in
            let parts = line.split(whereSeparator: { $0 == " " || $0 == "\t" })
            return parts.first == "SleepDisabled" && parts.last == "1"
        }
    }

    // MARK: Lid

    private func isLidClosed() -> Bool {
        let svc = IOServiceGetMatchingService(kIOMainPortDefault, IOServiceMatching("IOPMrootDomain"))
        guard svc != 0 else { return false }
        defer { IOObjectRelease(svc) }
        let v = IORegistryEntryCreateCFProperty(svc, "AppleClamshellState" as CFString,
                                                kCFAllocatorDefault, 0)?.takeRetainedValue()
        return (v as? Bool) ?? false
    }

    private func setLowPower(_ on: Bool) {
        sudoPmset(["lowpowermode", on ? "1" : "0"])
        ecoTurnedOnByUs = on
    }

    private func checkLid() {
        handleCommands()   // fallback in case a notification was missed
        let closed = isLidClosed()
        guard closed != lidClosed else { return }
        lidClosed = closed
        if closed {
            lidClosedAt = Date(); pctAtClose = lastPct; maxLimitedWhileClosed = 0
        } else if let start = lidClosedAt {
            saveRecap(since: start)
            lidClosedAt = nil
        }
        computeAndApply()
        sample()
        guard ecoEnabled else { return }
        if closed {
            if !ProcessInfo.processInfo.isLowPowerModeEnabled { setLowPower(true) }
        } else if ecoTurnedOnByUs {
            setLowPower(false)
        }
    }

    private func saveRecap(since start: Date) {
        let mins = Int(Date().timeIntervalSince(start) / 60)
        guard mins >= 1 else { return }
        let dur = mins >= 60 ? "\(mins / 60)h \(mins % 60)m" : "\(mins)m"
        var parts = [dur, "used \(Swift.max(0, pctAtClose - lastPct))% battery"]
        if maxLimitedWhileClosed > 0 { parts.append("\(maxLimitedWhileClosed) apps limited") }
        defaults.set(parts.joined(separator: " · "), forKey: "lastRecap")
    }

    // MARK: Planning + applying limits

    private func sample() {
        guard !sampling else { return }
        sampling = true
        sampleQueue.async {
            let g = sampleGroups()
            DispatchQueue.main.async {
                self.groups = g
                self.sampling = false
                self.computeAndApply()
            }
        }
    }

    private var isPaused: Bool { pauseUntil.map { $0 > Date() } ?? false }

    private var modeOn: Bool {
        switch activeMode {
        case .never: return false
        case .lidClosed: return lidClosed
        case .always: return true
        }
    }

    private var limitsActive: Bool { modeOn && !isPaused }

    private var limitedCount: Int {
        limitsActive ? plan.filter { $0.action != .none }.count + otherCount : 0
    }

    private func computeAndApply() {
        let active = limitsActive
        let procs = myProcesses()
        var children: [pid_t: [pid_t]] = [:]
        for (pid, ppid) in procs { children[ppid, default: []].append(pid) }
        let running = NSWorkspace.shared.runningApplications.filter { $0.activationPolicy == .regular }
        let frontPid = lidClosed ? nil : NSWorkspace.shared.frontmostApplication?.processIdentifier

        // An app's main process + its helpers
        func pidsOf(_ name: String, _ extra: [pid_t]) -> [pid_t] {
            var s = Set(extra.filter { kill($0, 0) == 0 })
            for app in running where app.localizedName == name {
                s.insert(app.processIdentifier)
                for c in children[app.processIdentifier] ?? [] { s.insert(c) }
            }
            return Array(s)
        }

        var merged = active ? tracked : [:]
        for g in groups {
            if var t = merged[g.name] {
                t.pids = Array(Set(t.pids + g.pids)); t.power = g.power; merged[g.name] = t
            } else { merged[g.name] = g }
        }
        let r = rules
        for (name, _) in r where merged[name] == nil {   // rule apps that aren't busy right now
            if let app = running.first(where: { $0.localizedName == name }) {
                merged[name] = AppGroup(name: name, icon: app.icon, pids: [], power: 0)
            }
        }

        var newPlan: [Planned] = []
        var newTracked: [String: AppGroup] = [:]
        for (name, g0) in merged {
            var g = g0
            g.pids = pidsOf(name, g.pids)
            guard !g.pids.isEmpty else { continue }
            g.peak = Swift.max(g.peak, g.power)
            if let rule = r[name] {
                newPlan.append(Planned(name: name, icon: g.icon, action: rule, reason: "your rule", pids: g.pids))
                if active { newTracked[name] = g }
                continue
            }
            let isFront = frontPid.map { g.pids.contains($0) } ?? false
            guard let hog = preset.hogAction, !isFront else { continue }
            let wasLimited = active && tracked[name] != nil
            guard g.power >= hogThreshold || wasLimited else { continue }
            let reason: String
            if !active { reason = "high power now (\(Int(g.power)))" }
            else if g.power < g.peak * 0.8 { reason = "used \(Int(g.peak)), now \(Int(g.power))" }
            else { reason = "using \(Int(g.power))" }
            newPlan.append(Planned(name: name, icon: g.icon, action: hog, reason: reason, pids: g.pids))
            if active { newTracked[name] = g }
        }
        plan = newPlan.sorted { ($0.action.strength, $0.name) > ($1.action.strength, $1.name) }
        tracked = active ? newTracked : [:]

        var target: [pid_t: LimitAction] = [:]
        let named = Set(plan.flatMap { $0.pids })
        var frontSet = Set<pid_t>()
        if let f = frontPid { frontSet.insert(f); for c in children[f] ?? [] { frontSet.insert(c) } }
        if active && preset.allECores {
            for pid in procs.keys where !named.contains(pid) && !frontSet.contains(pid) { target[pid] = .ecores }
        }
        if active {
            for p in plan where p.action != .none { for pid in p.pids { target[pid] = p.action } }
        }
        limiter.apply(target)

        let namedApps = Set(plan.map { $0.name })
        otherCount = preset.allECores
            ? running.filter { app in
                !namedApps.contains(app.localizedName ?? "") && !frontSet.contains(app.processIdentifier)
                && !systemNames.contains(app.localizedName ?? "") && app.processIdentifier != getpid()
              }.count
            : 0
        if lidClosed { maxLimitedWhileClosed = Swift.max(maxLimitedWhileClosed, limitedCount) }
        refresh()
    }

    // MARK: Menu actions

    @objc private func setRule(_ sender: NSMenuItem) {
        guard let pair = sender.representedObject as? [String], pair.count == 2 else { return }
        var r = rules
        if pair[1] == "auto" { r.removeValue(forKey: pair[0]) } else { r[pair[0]] = LimitAction(rawValue: pair[1]) }
        rules = r
        tracked.removeValue(forKey: pair[0])
        computeAndApply()
    }

    @objc private func setPreset(_ sender: NSMenuItem) {
        if let raw = sender.representedObject as? String, let p = Preset(rawValue: raw) { preset = p }
        tracked = [:]
        computeAndApply()
    }

    @objc private func setMode(_ sender: NSMenuItem) {
        if let raw = sender.representedObject as? String, let m = ActiveMode(rawValue: raw) { activeMode = m }
        computeAndApply()
    }

    @objc private func pauseHour() {
        pauseUntil = Date().addingTimeInterval(3600)
        computeAndApply()
    }

    @objc private func resume() {
        pauseUntil = nil
        computeAndApply()
    }

    @objc private func toggleLid() { setStayAwake(!sleepDisabled) }

    @objc private func toggleLowPowerNow() {
        let on = !ProcessInfo.processInfo.isLowPowerModeEnabled
        sudoPmset(["lowpowermode", on ? "1" : "0"])
        ecoTurnedOnByUs = false
        refresh()
    }

    @objc private func openBatterySettings() {
        if let url = URL(string: "x-apple.systempreferences:com.apple.Battery-Settings.extension") {
            NSWorkspace.shared.open(url)
        }
    }

    private func setStayAwake(_ on: Bool) {
        let newValue = on ? 1 : 0
        if !sudoPmset(["disablesleep", "\(newValue)"]) {
            let src = "do shell script \"/usr/bin/pmset -a disablesleep \(newValue)\" with administrator privileges"
            var err: NSDictionary?
            NSAppleScript(source: src)?.executeAndReturnError(&err)
        }
        refresh()
    }

    @objc private func toggleEco() {
        ecoEnabled.toggle()
        if !ecoEnabled && ecoTurnedOnByUs { setLowPower(false) }
        refresh()
    }


    // MARK: Menu building (laid out like Apple's battery menu, plus BatteryTime extras)

    private let menuWidth: CGFloat = 310

    private func item(_ title: String, _ action: Selector?, on: Bool = false, obj: Any? = nil) -> NSMenuItem {
        let i = NSMenuItem(title: title, action: action, keyEquivalent: "")
        i.target = self
        i.state = on ? .on : .off
        i.representedObject = obj
        return i
    }

    private func header(_ title: String) -> NSMenuItem {
        let i = NSMenuItem(title: title, action: nil, keyEquivalent: "")
        i.isEnabled = false
        return i
    }

    private func viewItem(_ v: NSView) -> NSMenuItem {
        let i = NSMenuItem()
        i.view = v
        return i
    }

    private func ruleMenu(for name: String) -> NSMenu {
        let r = rules[name]
        let m = NSMenu()
        m.autoenablesItems = false
        m.addItem(item("Follow Battery Saver", #selector(setRule(_:)), on: r == nil, obj: [name, "auto"]))
        m.addItem(.separator())
        for a in [LimitAction.ecores, .p50, .p25, .pause, .none] {
            m.addItem(item(a.title, #selector(setRule(_:)), on: r == a, obj: [name, a.rawValue]))
        }
        return m
    }

    /// App row: icon, name, grey detail, right-aligned tag
    private func appRow(_ name: String, icon: NSImage?, detail: String, tag: String) -> NSMenuItem {
        let tabs = NSMutableParagraphStyle()
        tabs.tabStops = [NSTextTab(textAlignment: .right, location: menuWidth - 40)]
        let row = NSMenuItem(title: name, action: nil, keyEquivalent: "")
        let s = NSMutableAttributedString(string: name, attributes: [.font: NSFont.menuFont(ofSize: 13)])
        if !detail.isEmpty {
            s.append(NSAttributedString(string: "  \(detail)", attributes: [
                .font: NSFont.systemFont(ofSize: 11), .foregroundColor: NSColor.secondaryLabelColor]))
        }
        if !tag.isEmpty {
            s.append(NSAttributedString(string: "\t\(tag)", attributes: [
                .font: NSFont.systemFont(ofSize: 11, weight: .medium), .foregroundColor: NSColor.controlAccentColor]))
        }
        s.addAttribute(.paragraphStyle, value: tabs, range: NSRange(location: 0, length: s.length))
        row.attributedTitle = s
        if let i = icon?.copy() as? NSImage { i.size = NSSize(width: 16, height: 16); row.image = i }
        else { row.image = NSImage(systemSymbolName: "app.dashed", accessibilityDescription: nil) }
        return row
    }

    private func timeString(_ d: Date) -> String {
        let f = DateFormatter(); f.dateFormat = "HH:mm"; return f.string(from: d)
    }

    private var frontmostNote: String {
        guard activeMode == .always, !lidClosed,
              let name = NSWorkspace.shared.frontmostApplication?.localizedName,
              rules[name] == nil else { return "" }
        return "\(name) isn't limited while you're using it"
    }

    func menuNeedsUpdate(_ menu: NSMenu) {
        menu.removeAllItems()
        let active = limitsActive
        let lowPower = ProcessInfo.processInfo.isLowPowerModeEnabled

        // ── Like Apple's menu ──
        menu.addItem(viewItem(HeaderRow(title: "Battery", value: "\(lastPct)%", width: menuWidth)))
        menu.addItem(viewItem(TextRow(powerSourceText, width: menuWidth)))
        menu.addItem(viewItem(TextRow(timeText, width: menuWidth, bold: true)))
        menu.addItem(.separator())
        menu.addItem(viewItem(SwitchRow(title: "Low Power Mode", on: lowPower, width: menuWidth,
                                        target: self, action: #selector(toggleLowPowerNow))))
        menu.addItem(.separator())

        // Using Significant Energy (Apple's list) + what BatteryTime does with each app
        menu.addItem(viewItem(SectionRow("Using Significant Energy", width: menuWidth)))
        let significant = groups.filter { $0.power >= 10 }.prefix(6)
        let planned = Dictionary(plan.map { ($0.name, $0) }, uniquingKeysWith: { a, _ in a })
        if significant.isEmpty {
            menu.addItem(viewItem(TextRow("No Apps Using Significant Energy", width: menuWidth)))
        }
        for g in significant {
            var tag = ""
            var detail = ""
            if let p = planned[g.name] {
                if p.action == .none { detail = "never limited" }
                else if active { tag = p.action.tag; detail = p.reason == "your rule" ? "your rule" : "" }
                else { tag = "\(p.action.tag) on lid close" }
            } else if preset.allECores && modeOn && !isPaused {
                tag = active ? "E-cores" : ""
            }
            if !active && tag.isEmpty && preset.allECores && activeMode == .lidClosed { tag = "E-cores on lid close" }
            let row = appRow(g.name, icon: g.icon, detail: detail, tag: tag)
            row.submenu = ruleMenu(for: g.name)
            menu.addItem(row)
        }
        menu.addItem(.separator())

        // ── BatteryTime: Battery Saver ──
        menu.addItem(viewItem(HeaderRow(title: "Battery Saver", value: preset.title, width: menuWidth)))
        let nothingConfigured = preset == .off && rules.isEmpty
        let status: (String, String, Bool)
        if activeMode == .never || nothingConfigured {
            status = ("Limits are off", nothingConfigured ? "Choose a level in Battery Saver Level" : "Active is set to Never", false)
        } else if isPaused, let until = pauseUntil {
            status = ("Limits paused", "Until \(timeString(until))", false)
        } else if active {
            status = ("Saving power · \(limitedCount) apps limited",
                      activeMode == .always ? "Always on" : "Because the lid is closed", true)
        } else {
            status = ("Limits are off right now", "They start when you close the lid", false)
        }
        menu.addItem(viewItem(StatusRow(title: status.0, subtitle: status.1, accent: status.2, width: menuWidth)))

        if activeMode != .never && !nothingConfigured {
            // Rule apps that aren't in the energy list above
            let shownNames = Set(significant.map { $0.name })
            for p in plan where !shownNames.contains(p.name) {
                let row = appRow(p.name, icon: p.icon, detail: p.reason,
                                 tag: p.action == .none ? "Never" : (active ? p.action.tag : "\(p.action.tag) on lid close"))
                row.submenu = ruleMenu(for: p.name)
                menu.addItem(row)
            }
            let grid = NSImage(systemSymbolName: "square.grid.2x2", accessibilityDescription: nil)
            if preset.allECores {
                if active && otherCount > 0 {
                    menu.addItem(appRow("\(otherCount) other apps", icon: grid, detail: "", tag: "E-cores"))
                } else if !active {
                    menu.addItem(appRow("All your apps", icon: grid, detail: "", tag: "E-cores on lid close"))
                }
            }
            if !frontmostNote.isEmpty { menu.addItem(header(frontmostNote)) }
            if isPaused { menu.addItem(item("Resume Limits Now", #selector(resume))) }
            else if active { menu.addItem(item("Pause Limits for 1 Hour", #selector(pauseHour))) }
        }

        let presetRow = NSMenuItem(title: "Battery Saver Level", action: nil, keyEquivalent: "")
        let pm = NSMenu(); pm.autoenablesItems = false
        for p in [Preset.off, .light, .balanced, .max] {
            pm.addItem(item(p.title, #selector(setPreset(_:)), on: preset == p, obj: p.rawValue))
            pm.addItem(header("    \(p.detail)"))
        }
        presetRow.submenu = pm
        menu.addItem(presetRow)

        let modeRow = NSMenuItem(title: "Active: \(activeMode.title)", action: nil, keyEquivalent: "")
        let mm = NSMenu(); mm.autoenablesItems = false
        for m in [ActiveMode.never, .lidClosed, .always] {
            mm.addItem(item(m.title, #selector(setMode(_:)), on: activeMode == m, obj: m.rawValue))
        }
        modeRow.submenu = mm
        menu.addItem(modeRow)

        let r = rules
        let rulesRow = NSMenuItem(title: "App Rules (\(r.count))", action: nil, keyEquivalent: "")
        let rm = NSMenu(); rm.autoenablesItems = false
        if r.isEmpty { rm.addItem(header("No rules yet")) }
        for (name, a) in r.sorted(by: { $0.key < $1.key }) {
            let row = NSMenuItem(title: "\(name) — \(a.tag)", action: nil, keyEquivalent: "")
            row.submenu = ruleMenu(for: name)
            rm.addItem(row)
        }
        rm.addItem(.separator())
        rm.addItem(header("Add a Rule For"))
        let candidates = NSWorkspace.shared.runningApplications
            .filter { $0.activationPolicy == .regular && $0.processIdentifier != getpid() }
            .compactMap { $0.localizedName }
            .filter { r[$0] == nil && !systemNames.contains($0) }
        for name in Array(Set(candidates)).sorted() {
            let row = NSMenuItem(title: name, action: nil, keyEquivalent: "")
            row.submenu = ruleMenu(for: name)
            rm.addItem(row)
        }
        rulesRow.submenu = rm
        menu.addItem(rulesRow)

        if let recap = defaults.string(forKey: "lastRecap") {
            menu.addItem(viewItem(TextRow("Last lid close: \(recap)", width: menuWidth)))
        }
        menu.addItem(.separator())

        // ── Lid ──
        menu.addItem(viewItem(SwitchRow(title: "Stay On With Lid Closed", on: sleepDisabled, width: menuWidth,
                                        target: self, action: #selector(toggleLid))))
        menu.addItem(viewItem(SwitchRow(title: "Low Power Mode When Lid Closed", on: ecoEnabled, width: menuWidth,
                                        target: self, action: #selector(toggleEco))))
        menu.addItem(.separator())

        // ── Like Apple's menu ──
        menu.addItem(item("Battery Settings…", #selector(openBatterySettings)))
        menu.addItem(.separator())
        menu.addItem(NSMenuItem(title: "Quit BatteryTime", action: #selector(NSApplication.terminate(_:)), keyEquivalent: "q"))
    }

    // MARK: Battery state + menu bar title

    private var powerSourceText = "Power Source: Battery"
    private var timeText = ""
    private var lastPublished = ""

    private func hm(_ minutes: Int) -> String {
        String(format: "%d:%02d", minutes / 60, minutes % 60)
    }

    func refresh() {
        sleepDisabled = readSleepDisabled()
        var prefix = sleepDisabled ? "☕ " : ""
        if limitedCount > 0 { prefix += "🐢 " }

        guard let blob = IOPSCopyPowerSourcesInfo()?.takeRetainedValue(),
              let list = IOPSCopyPowerSourcesList(blob)?.takeRetainedValue() as? [CFTypeRef],
              let desc = list
                .compactMap({ IOPSGetPowerSourceDescription(blob, $0)?.takeUnretainedValue() as? [String: Any] })
                .first(where: { ($0[kIOPSTypeKey] as? String) == kIOPSInternalBatteryType })
        else {
            statusItem.button?.title = prefix + "No battery"
            timeText = "No internal battery found"
            publish(title: "No Battery", symbol: "powerplug")
            return
        }

        let cur = desc[kIOPSCurrentCapacityKey] as? Int ?? 0
        let max = desc[kIOPSMaxCapacityKey] as? Int ?? 100
        let pct = max > 0 ? cur * 100 / max : cur
        lastPct = pct
        let charging = desc[kIOPSIsChargingKey] as? Bool ?? false
        let onAC = (desc[kIOPSPowerSourceStateKey] as? String) == kIOPSACPowerValue
        powerSourceText = onAC ? "Power Source: Power Adapter" : "Power Source: Battery"

        let level = pct >= 88 ? "100" : pct >= 63 ? "75" : pct >= 38 ? "50" : pct >= 13 ? "25" : "0"
        var symbol = "battery.\(level)percent"
        let title: String
        let controlTitle: String
        if charging {
            let m = desc[kIOPSTimeToFullChargeKey] as? Int ?? -1
            title = m > 0 ? "⚡ \(hm(m))" : "⚡ …"
            timeText = m > 0 ? "\(hm(m)) Until Full" : "Charging — Calculating…"
            controlTitle = m > 0 ? "Full in \(hm(m))" : "\(pct)% Charging"
            symbol = "battery.100percent.bolt"
        } else if onAC {
            title = "🔌 \(pct)%"
            timeText = "Not Charging"
            controlTitle = "\(pct)% · Plugged In"
        } else {
            let m = desc[kIOPSTimeToEmptyKey] as? Int ?? -1
            title = m > 0 ? "🔋 \(hm(m))" : "🔋 …"
            timeText = m > 0 ? "\(hm(m)) Remaining" : "Calculating Time Remaining…"
            controlTitle = m > 0 ? "\(hm(m)) Left" : "\(pct)%"
        }
        statusItem.button?.title = prefix + title
        publish(title: controlTitle, symbol: symbol)
    }

    /// Share state with the Control Center controls and refresh them when it changes
    private func publish(title: String, symbol: String) {
        let d = defaults
        d.set(sleepDisabled, forKey: Shared.Key.sleepDisabled)
        d.set(title, forKey: Shared.Key.batteryTitle)
        d.set(symbol, forKey: Shared.Key.batterySymbol)
        let sig = "\(sleepDisabled)|\(title)|\(symbol)|\(preset.rawValue)|\(ecoEnabled)"
        if sig != lastPublished {
            lastPublished = sig
            ControlCenter.shared.reloadAllControls()
        }
    }
}

// MARK: - Native-looking menu rows

private let rowInset: CGFloat = 14

final class HeaderRow: NSView {
    init(title: String, value: String, width: CGFloat) {
        super.init(frame: NSRect(x: 0, y: 0, width: width, height: 24))
        let l = NSTextField(labelWithString: title)
        l.font = .boldSystemFont(ofSize: 13)
        let r = NSTextField(labelWithString: value)
        r.font = .systemFont(ofSize: 13)
        r.textColor = .secondaryLabelColor
        for v in [l, r] { v.translatesAutoresizingMaskIntoConstraints = false; addSubview(v) }
        NSLayoutConstraint.activate([
            l.leadingAnchor.constraint(equalTo: leadingAnchor, constant: rowInset),
            l.centerYAnchor.constraint(equalTo: centerYAnchor),
            r.trailingAnchor.constraint(equalTo: trailingAnchor, constant: -rowInset),
            r.centerYAnchor.constraint(equalTo: centerYAnchor)])
    }
    required init?(coder: NSCoder) { fatalError() }
}

final class TextRow: NSView {
    init(_ text: String, width: CGFloat, bold: Bool = false) {
        super.init(frame: NSRect(x: 0, y: 0, width: width, height: 20))
        let l = NSTextField(labelWithString: text)
        l.font = bold ? .systemFont(ofSize: 13, weight: .medium) : .systemFont(ofSize: 12)
        l.textColor = bold ? .labelColor : .secondaryLabelColor
        l.lineBreakMode = .byTruncatingTail
        l.translatesAutoresizingMaskIntoConstraints = false
        addSubview(l)
        NSLayoutConstraint.activate([
            l.leadingAnchor.constraint(equalTo: leadingAnchor, constant: rowInset),
            l.trailingAnchor.constraint(lessThanOrEqualTo: trailingAnchor, constant: -rowInset),
            l.centerYAnchor.constraint(equalTo: centerYAnchor)])
    }
    required init?(coder: NSCoder) { fatalError() }
}

final class SectionRow: NSView {
    init(_ text: String, width: CGFloat) {
        super.init(frame: NSRect(x: 0, y: 0, width: width, height: 22))
        let l = NSTextField(labelWithString: text)
        l.font = .systemFont(ofSize: 12, weight: .semibold)
        l.textColor = .secondaryLabelColor
        l.translatesAutoresizingMaskIntoConstraints = false
        addSubview(l)
        NSLayoutConstraint.activate([
            l.leadingAnchor.constraint(equalTo: leadingAnchor, constant: rowInset),
            l.bottomAnchor.constraint(equalTo: bottomAnchor, constant: -2)])
    }
    required init?(coder: NSCoder) { fatalError() }
}

final class StatusRow: NSView {
    init(title: String, subtitle: String, accent: Bool, width: CGFloat) {
        super.init(frame: NSRect(x: 0, y: 0, width: width, height: 52))
        let box = NSView()
        box.wantsLayer = true
        box.layer?.cornerRadius = 8
        box.layer?.backgroundColor = (accent ? NSColor.controlAccentColor.withAlphaComponent(0.15)
                                             : NSColor.quaternaryLabelColor).cgColor
        let t = NSTextField(labelWithString: title)
        t.font = .systemFont(ofSize: 13, weight: .semibold)
        t.textColor = accent ? .controlAccentColor : .labelColor
        let s = NSTextField(labelWithString: subtitle)
        s.font = .systemFont(ofSize: 11)
        s.textColor = .secondaryLabelColor
        for v in [box, t, s] { v.translatesAutoresizingMaskIntoConstraints = false }
        addSubview(box); box.addSubview(t); box.addSubview(s)
        NSLayoutConstraint.activate([
            box.leadingAnchor.constraint(equalTo: leadingAnchor, constant: 8),
            box.trailingAnchor.constraint(equalTo: trailingAnchor, constant: -8),
            box.topAnchor.constraint(equalTo: topAnchor, constant: 4),
            box.bottomAnchor.constraint(equalTo: bottomAnchor, constant: -4),
            t.leadingAnchor.constraint(equalTo: box.leadingAnchor, constant: 10),
            t.topAnchor.constraint(equalTo: box.topAnchor, constant: 6),
            s.leadingAnchor.constraint(equalTo: t.leadingAnchor),
            s.topAnchor.constraint(equalTo: t.bottomAnchor, constant: 1)])
    }
    required init?(coder: NSCoder) { fatalError() }
}

final class SwitchRow: NSView {
    private let toggle = NSSwitch()
    private weak var target: NSObject?
    private let action: Selector

    init(title: String, on: Bool, width: CGFloat, target: NSObject, action: Selector) {
        self.target = target
        self.action = action
        super.init(frame: NSRect(x: 0, y: 0, width: width, height: 28))
        let l = NSTextField(labelWithString: title)
        l.font = .menuFont(ofSize: 13)
        toggle.controlSize = .mini
        toggle.state = on ? .on : .off
        toggle.target = self
        toggle.action = #selector(flipped)
        for v in [l, toggle] { v.translatesAutoresizingMaskIntoConstraints = false; addSubview(v) }
        NSLayoutConstraint.activate([
            l.leadingAnchor.constraint(equalTo: leadingAnchor, constant: rowInset),
            l.centerYAnchor.constraint(equalTo: centerYAnchor),
            toggle.trailingAnchor.constraint(equalTo: trailingAnchor, constant: -rowInset),
            toggle.centerYAnchor.constraint(equalTo: centerYAnchor)])
    }
    required init?(coder: NSCoder) { fatalError() }

    @objc private func flipped() {
        _ = target?.perform(action)
    }
}

let app = NSApplication.shared
let delegate = AppDelegate()
app.delegate = delegate
app.setActivationPolicy(.accessory)   // no Dock icon
app.run()
