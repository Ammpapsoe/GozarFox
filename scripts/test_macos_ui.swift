import AppKit
import CoreGraphics
import Foundation

struct SmokeError: Error, CustomStringConvertible {
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

  let process = Process()
  process.executableURL = URL(fileURLWithPath: executable)
  var environment = ProcessInfo.processInfo.environment
  environment["GOZAR_CORE"] = core
  environment["GOZAR_UI_TEST_LOG"] = logPath
  process.environment = environment
  try process.run()
  defer { if process.isRunning { process.terminate() } }
  let pid = process.processIdentifier
  guard waitUntil(60, { process.isRunning && events().contains("state") && visibleWindow(for: pid) }) else {
    throw SmokeError("The macOS window or its simulated core did not start.")
  }
  guard let app = NSRunningApplication(processIdentifier: pid) else {
    throw SmokeError("Could not inspect the native macOS application.")
  }
  if hidden {
    guard app.hide(), waitUntil(10, { app.isHidden }) else {
      throw SmokeError("Could not hide the native macOS application.")
    }
  }
  guard app.terminate() else {
    throw SmokeError("Could not request a normal macOS quit.")
  }
  guard waitUntil(60, { !process.isRunning }) else {
    throw SmokeError("The macOS application did not quit.")
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
} catch {
  fputs("\(error)\n", stderr)
  exit(1)
}
