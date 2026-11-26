import Cocoa
import Network
import CoreImage.CIFilterBuiltins

/// What the phone companion can read and change. AppDelegate implements it.
protocol CompanionHost: AnyObject {
    /// The state shown on the phone (docs/companion-protocol.md), built on the main thread
    func companionState() -> [String: Any]
    /// Runs one command from the phone on the main thread. Returns an error message, or nil when it worked.
    func companionCommand(_ command: [String: Any]) -> String?
    /// The icon of a running app, for the phone's app rows
    func companionIcon(app: String) -> NSImage?
}

/// A small HTTP server on the local network, so the phone companion can see and control BatteryTime while the lid is
/// closed. It's advertised over Bonjour as _batterytime._tcp. Every request needs the pairing code as a bearer token.
final class CompanionServer {
    static let port: UInt16 = 47070
    static let serviceType = "_batterytime._tcp"
    static let apiVersion = 1

    weak var host: CompanionHost?
    private var listener: NWListener?
    private let queue = DispatchQueue(label: "batterytime.companion")
    private var failures: [Date] = []   // wrong codes, for rate limiting (queue only)
    private let defaults = UserDefaults.standard

    // MARK: Settings

    var enabled: Bool {
        get { defaults.bool(forKey: "companion.enabled") }
        set { defaults.set(newValue, forKey: "companion.enabled"); newValue ? start() : stop() }
    }

    /// The pairing code: 8 characters without look-alikes (I/O/0/1), shown as XXXX-XXXX
    private(set) var code: String {
        get {
            if let c = defaults.string(forKey: "companion.code"), c.count == 8 { return c }
            let c = CompanionServer.newCode()
            defaults.set(c, forKey: "companion.code")
            return c
        }
        set { defaults.set(newValue, forKey: "companion.code") }
    }

    var displayCode: String { let c = code; return "\(c.prefix(4))-\(c.suffix(4))" }

    /// A new code: phones paired with the old one stop working
    func resetCode() { code = CompanionServer.newCode() }

    private static func newCode() -> String {
        let alphabet = Array("ABCDEFGHJKLMNPQRSTUVWXYZ23456789")
        return String((0..<8).map { _ in alphabet[Int.random(in: 0..<alphabet.count)] })
    }

    var macName: String { Host.current().localizedName ?? "Mac" }

    /// The link in the pairing QR code, read by the phone app
    var pairingLink: String {
        var c = URLComponents()
        c.scheme = "batterytime"
        c.host = "pair"
        c.queryItems = [
            URLQueryItem(name: "h", value: CompanionServer.localAddress() ?? ""),
            URLQueryItem(name: "p", value: "\(CompanionServer.port)"),
            URLQueryItem(name: "c", value: code),
            URLQueryItem(name: "n", value: macName),
        ]
        return c.string ?? ""
    }

    var isRunning: Bool { listener != nil }

    // MARK: Listener

    func start() {
        guard listener == nil else { return }
        do {
            let params = NWParameters.tcp
            params.allowLocalEndpointReuse = true
            params.includePeerToPeer = false
            let l = try NWListener(using: params, on: NWEndpoint.Port(rawValue: CompanionServer.port)!)
            l.service = NWListener.Service(name: macName, type: CompanionServer.serviceType)
            l.newConnectionHandler = { [weak self] conn in self?.accept(conn) }
            l.stateUpdateHandler = { [weak self] state in
                if case .failed = state {
                    // e.g. the port is taken or the network changed: try again shortly
                    DispatchQueue.main.async {
                        self?.stop()
                        DispatchQueue.main.asyncAfter(deadline: .now() + 5) {
                            if self?.enabled == true { self?.start() }
                        }
                    }
                }
            }
            l.start(queue: queue)
            listener = l
        } catch {
            NSLog("BatteryTime companion: can't listen: \(error)")
        }
    }

    func stop() {
        listener?.cancel()
        listener = nil
    }

    // MARK: HTTP

    private struct Request {
        let method: String
        let path: String
        let query: [String: String]
        let headers: [String: String]
        let body: Data
    }

    private func accept(_ conn: NWConnection) {
        conn.start(queue: queue)
        receive(conn, buffer: Data())
        // Drop connections that never send a full request
        queue.asyncAfter(deadline: .now() + 10) { if conn.state != .cancelled { conn.cancel() } }
    }

    private func receive(_ conn: NWConnection, buffer: Data) {
        conn.receive(minimumIncompleteLength: 1, maximumLength: 64 * 1024) { [weak self] data, _, done, error in
            guard let self = self else { conn.cancel(); return }
            var buf = buffer
            if let data = data { buf.append(data) }
            if buf.count > 256 * 1024 { self.send(conn, 413, ["error": "too large"]); return }
            if let req = self.parse(buf) {
                self.handle(req, conn)
            } else if done || error != nil {
                conn.cancel()
            } else {
                self.receive(conn, buffer: buf)
            }
        }
    }

    /// A full request in [data], or nil while more bytes are needed
    private func parse(_ data: Data) -> Request? {
        let sep = Data("\r\n\r\n".utf8)
        guard let end = data.range(of: sep) else { return nil }
        guard let head = String(data: data[..<end.lowerBound], encoding: .utf8) else { return nil }
        var lines = head.components(separatedBy: "\r\n")
        let first = lines.removeFirst().split(separator: " ")
        guard first.count >= 2 else { return nil }
        var headers: [String: String] = [:]
        for line in lines {
            guard let colon = line.firstIndex(of: ":") else { continue }
            headers[line[..<colon].lowercased()] = line[line.index(after: colon)...].trimmingCharacters(in: .whitespaces)
        }
        let length = Int(headers["content-length"] ?? "0") ?? 0
        let bodyStart = end.upperBound
        guard data.count - bodyStart >= length else { return nil }
        let target = URLComponents(string: String(first[1]))
        var query: [String: String] = [:]
        for item in target?.queryItems ?? [] { query[item.name] = item.value ?? "" }
        return Request(method: String(first[0]), path: target?.path ?? "/", query: query, headers: headers,
                       body: data[bodyStart..<(bodyStart + length)])
    }

    private func authorized(_ req: Request) -> Bool {
        let now = Date()
        failures = failures.filter { now.timeIntervalSince($0) < 60 }
        if failures.count >= 10 { return false }   // 10 wrong codes a minute: wait
        let given = (req.headers["authorization"] ?? "").replacingOccurrences(of: "Bearer ", with: "")
            .replacingOccurrences(of: "-", with: "").uppercased()
        let ok = !given.isEmpty && constantTimeEqual(given, code)
        if !ok { failures.append(now) }
        return ok
    }

    private func constantTimeEqual(_ a: String, _ b: String) -> Bool {
        let x = Array(a.utf8), y = Array(b.utf8)
        guard x.count == y.count else { return false }
        var diff: UInt8 = 0
        for i in 0..<x.count { diff |= x[i] ^ y[i] }
        return diff == 0
    }

    private func handle(_ req: Request, _ conn: NWConnection) {
        if req.method == "GET" && req.path == "/v1/hello" {
            // No code needed: lets the phone check it found BatteryTime before asking for the code
            send(conn, 200, ["app": "BatteryTime", "v": CompanionServer.apiVersion, "name": macName])
            return
        }
        guard authorized(req) else {
            send(conn, 401, ["error": "Wrong pairing code"])
            return
        }
        switch (req.method, req.path) {
        case ("GET", "/v1/state"):
            onMain { $0.companionState() } reply: { self.send(conn, 200, $0) }
        case ("POST", "/v1/command"):
            guard let cmd = (try? JSONSerialization.jsonObject(with: req.body)) as? [String: Any] else {
                send(conn, 400, ["error": "Bad command"])
                return
            }
            onMain { host -> (Int, [String: Any]) in
                if let err = host.companionCommand(cmd) { return (400, ["error": err]) }
                return (200, host.companionState())
            } reply: { self.send(conn, $0.0, $0.1) }
        case ("GET", "/v1/icon"):
            let name = req.query["app"] ?? ""
            onMain { $0.companionIcon(app: name) } reply: { image in
                if let png = image.flatMap({ CompanionServer.png($0, size: 64) }) {
                    self.sendRaw(conn, 200, "image/png", png, cache: true)
                } else {
                    self.send(conn, 404, ["error": "No icon"])
                }
            }
        default:
            send(conn, 404, ["error": "Not found"])
        }
    }

    /// Runs [work] on the main thread (where AppDelegate's state lives) and [reply] back on the server queue
    private func onMain<T>(_ work: @escaping (CompanionHost) -> T, reply: @escaping (T) -> Void) {
        DispatchQueue.main.async { [weak self] in
            guard let self = self, let host = self.host else { return }
            let result = work(host)
            self.queue.async { reply(result) }
        }
    }

    private func send(_ conn: NWConnection, _ status: Int, _ json: [String: Any]) {
        let body = (try? JSONSerialization.data(withJSONObject: json, options: [.sortedKeys])) ?? Data("{}".utf8)
        sendRaw(conn, status, "application/json", body, cache: false)
    }

    private func sendRaw(_ conn: NWConnection, _ status: Int, _ type: String, _ body: Data, cache: Bool) {
        let reason = [200: "OK", 400: "Bad Request", 401: "Unauthorized", 404: "Not Found", 413: "Too Large"][status] ?? "OK"
        var head = "HTTP/1.1 \(status) \(reason)\r\n"
        head += "Content-Type: \(type)\r\nContent-Length: \(body.count)\r\nConnection: close\r\n"
        head += cache ? "Cache-Control: max-age=3600\r\n" : "Cache-Control: no-store\r\n"
        head += "\r\n"
        var out = Data(head.utf8)
        out.append(body)
        conn.send(content: out, completion: .contentProcessed { _ in conn.cancel() })
    }

    // MARK: Helpers

    static func png(_ image: NSImage, size: CGFloat) -> Data? {
        let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: Int(size), pixelsHigh: Int(size),
                                   bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true, isPlanar: false,
                                   colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0)
        guard let rep = rep else { return nil }
        NSGraphicsContext.saveGraphicsState()
        NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: rep)
        image.draw(in: NSRect(x: 0, y: 0, width: size, height: size))
        NSGraphicsContext.restoreGraphicsState()
        return rep.representation(using: .png, properties: [:])
    }

    /// The Mac's IPv4 address on Wi-Fi or Ethernet, for the pairing link
    static func localAddress() -> String? {
        var ifaddr: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&ifaddr) == 0, let first = ifaddr else { return nil }
        defer { freeifaddrs(ifaddr) }
        var found: [String: String] = [:]
        for ptr in sequence(first: first, next: { $0.pointee.ifa_next }) {
            let ifa = ptr.pointee
            guard let addr = ifa.ifa_addr, addr.pointee.sa_family == UInt8(AF_INET),
                  (ifa.ifa_flags & UInt32(IFF_UP)) != 0, (ifa.ifa_flags & UInt32(IFF_LOOPBACK)) == 0 else { continue }
            var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            getnameinfo(addr, socklen_t(addr.pointee.sa_len), &host, socklen_t(host.count), nil, 0, NI_NUMERICHOST)
            let name = String(cString: ifa.ifa_name)
            found[name] = String(decoding: host.prefix(while: { $0 != 0 }).map { UInt8(bitPattern: $0) }, as: UTF8.self)
        }
        return found["en0"] ?? found["en1"] ?? found.filter { $0.key.hasPrefix("en") }.values.first
    }

    static func qrImage(_ text: String, size: CGFloat) -> NSImage? {
        let f = CIFilter.qrCodeGenerator()
        f.message = Data(text.utf8)
        f.correctionLevel = "M"
        guard let out = f.outputImage else { return nil }
        let scale = size / out.extent.width
        let scaled = out.transformed(by: CGAffineTransform(scaleX: scale, y: scale))
        let rep = NSCIImageRep(ciImage: scaled)
        let img = NSImage(size: rep.size)
        img.addRepresentation(rep)
        return img
    }
}

/// The "Phone Companion…" window: on/off, the pairing QR code, the address and code to type by hand
final class CompanionWindowController: NSWindowController {
    private let server: CompanionServer
    private let qr = NSImageView()
    private let address = NSTextField(labelWithString: "")
    private let codeLabel = NSTextField(labelWithString: "")
    private let toggle = NSSwitch()
    private let hint = NSTextField(wrappingLabelWithString: "")

    init(server: CompanionServer) {
        self.server = server
        let w = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 340, height: 500),
                         styleMask: [.titled, .closable], backing: .buffered, defer: false)
        w.title = "Phone Companion"
        w.isReleasedWhenClosed = false
        super.init(window: w)
        build()
        update()
    }
    required init?(coder: NSCoder) { fatalError() }

    private func build() {
        guard let content = window?.contentView else { return }
        let title = NSTextField(labelWithString: "Control BatteryTime from your phone")
        title.font = .boldSystemFont(ofSize: 14)
        let enableLabel = NSTextField(labelWithString: "Allow phone control")
        toggle.target = self
        toggle.action = #selector(flip)
        let toggleRow = NSStackView(views: [enableLabel, NSView(), toggle])
        toggleRow.distribution = .fill
        qr.imageScaling = .scaleProportionallyUpOrDown
        qr.widthAnchor.constraint(equalToConstant: 220).isActive = true
        qr.heightAnchor.constraint(equalToConstant: 220).isActive = true
        for l in [address, codeLabel] { l.isSelectable = true; l.alignment = .center }
        codeLabel.font = .monospacedSystemFont(ofSize: 22, weight: .semibold)
        address.textColor = .secondaryLabelColor
        hint.font = .systemFont(ofSize: 11)
        hint.textColor = .secondaryLabelColor
        hint.alignment = .center
        let reset = NSButton(title: "New Code", target: self, action: #selector(newCode))
        let stack = NSStackView(views: [title, toggleRow, qr, codeLabel, address, hint, reset])
        stack.orientation = .vertical
        stack.alignment = .centerX
        stack.spacing = 10
        stack.edgeInsets = NSEdgeInsets(top: 18, left: 20, bottom: 18, right: 20)
        stack.translatesAutoresizingMaskIntoConstraints = false
        toggleRow.widthAnchor.constraint(equalTo: stack.widthAnchor, constant: -40).isActive = true
        hint.widthAnchor.constraint(equalTo: stack.widthAnchor, constant: -40).isActive = true
        content.addSubview(stack)
        NSLayoutConstraint.activate([
            stack.leadingAnchor.constraint(equalTo: content.leadingAnchor),
            stack.trailingAnchor.constraint(equalTo: content.trailingAnchor),
            stack.topAnchor.constraint(equalTo: content.topAnchor),
            stack.bottomAnchor.constraint(lessThanOrEqualTo: content.bottomAnchor)])
    }

    func update() {
        let on = server.enabled
        toggle.state = on ? .on : .off
        qr.image = on ? CompanionServer.qrImage(server.pairingLink, size: 220) : nil
        qr.isHidden = !on
        codeLabel.stringValue = on ? server.displayCode : ""
        let addr = CompanionServer.localAddress()
        address.stringValue = on ? "\(addr ?? "No network") · port \(CompanionServer.port)" : ""
        hint.stringValue = on
            ? "In the BatteryTime app on your phone, tap Pair and scan this code. Your phone and Mac need to be on the same Wi-Fi."
            : "Turn on to see and control BatteryTime from your phone while the lid is closed."
    }

    @objc private func flip() {
        server.enabled = toggle.state == .on
        update()
    }

    @objc private func newCode() {
        server.resetCode()
        update()
    }
}
