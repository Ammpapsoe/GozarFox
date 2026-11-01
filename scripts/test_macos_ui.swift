import AppKit
import CoreGraphics
import Foundation

struct SmokeError: Error, CustomStringConvertible {
  let description: String
  init(_ description: String) { self.description = description }
}

struct SmokeUnavailable: Error, CustomStringConvertible {
  let description: String
  init(_ description: String) { self.description = description }
}

func waitUntil(_ seconds: TimeInterval, _ condition: () -> Bool) -> Bool {
  let deadline = Date().addingTimeInterval(seconds)
  while Date() < deadline {
    if condition() { return true }
    RunLoop.current.run(until: Date().addingTimeInterval(0.2))
  }
  return condition()
}

func visibleWindow(for pid: pid_t) -> Bool {
  guard let windows = CGWindowListCopyWindowInfo(.optionOnScreenOnly, kCGNullWindowID) as? [[String: Any]] else { return false }
  return windows.contains { window in
    let owner = (window[kCGWindowOwnerPID as String] as? NSNumber)?.int32Value
    let layer = (window[kCGWindowLayer as String] as? NSNumber)?.intValue
    return owner == pid && layer == 0
  }
}

func runSmoke() throws {
  let arguments = CommandLine.arguments
  guard arguments.count == 5 else {
    throw SmokeError("Expected application executable, simulated core, event log and mode.")
  }
  let executable = arguments[1]
  let core = arguments[2]
  let logPath = arguments[3]
  let hidden = arguments[4] == "hidden"
  try? FileManager.default.removeItem(atPath: logPath)

  func events() -> [String] {
    guard let text = try? String(contentsOfFile: logPath, encoding: .utf8) else { return [] }
    return text.split(separator: "\n").map(String.init)
  }

  let bundle = URL(fileURLWithPath: executable).deletingLastPathComponent()
    .deletingLastPathComponent().deletingLastPathComponent()
  let configuration = NSWorkspace.OpenConfiguration()
  configuration.activates = true
  configuration.createsNewApplicationInstance = true
  configuration.addsToRecentItems = false
  configuration.environment = ["GOZAR_CORE": core, "GOZAR_UI_TEST_LOG": logPath]
  var launched: NSRunningApplication?
  var launchError: Error?
  var launchFinished = false
  NSWorkspace.shared.openApplication(at: bundle, configuration: configuration) { app, error in
    launched = app
    launchError = error
    launchFinished = true
  }
  guard waitUntil(30, { launchFinished }), let app = launched else {
    throw SmokeError("Could not launch the native macOS application: \(String(describing: launchError))")
  }
  defer { if !app.isTerminated { _ = app.forceTerminate() } }
  let pid = app.processIdentifier
  guard waitUntil(60, { !app.isTerminated && events().contains("state") && visibleWindow(for: pid) }) else {
    throw SmokeError("The macOS window or its simulated core did not start.")
  }
  if hidden {
    let sent = app.hide()
    guard waitUntil(10, { app.isHidden && !visibleWindow(for: pid) }) else {
      let message = "Could not hide the native macOS application (policy \(app.activationPolicy.rawValue), request \(sent))."
      if !sent { throw SmokeUnavailable(message) }
      throw SmokeError(message)
    }
  }
  guard app.terminate() else {
    throw SmokeError("Could not request a normal macOS quit.")
  }
  guard waitUntil(60, { app.isTerminated }) else {
    throw SmokeError("The macOS application did not quit (hidden: \(app.isHidden), visible window: \(visibleWindow(for: pid)), simulated events: \(events().joined(separator: ", ")).")
  }
  let recorded = events()
  guard let disconnect = recorded.firstIndex(of: "disconnect"),
        let quit = recorded.firstIndex(of: "quit"), quit > disconnect else {
    throw SmokeError("The simulated core did not disconnect before quitting.")
  }
  print("Native macOS \(hidden ? "hidden" : "visible") quit passed with a simulated core.")
}

do {
  try runSmoke()
} catch let error as SmokeUnavailable {
  fputs("\(error)\n", stderr)
  exit(77)
} catch {
  fputs("\(error)\n", stderr)
  exit(1)
}
