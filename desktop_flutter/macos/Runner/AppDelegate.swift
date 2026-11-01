import Cocoa
import FlutterMacOS

@main
class AppDelegate: FlutterAppDelegate {
  private var mainWindowClosed = false

  override func applicationWillFinishLaunching(_ notification: Notification) {
    super.applicationWillFinishLaunching(notification)
    NotificationCenter.default.addObserver(
      self,
      selector: #selector(mainWindowWillClose(_:)),
      name: NSWindow.willCloseNotification,
      object: mainFlutterWindow
    )
  }

  @objc private func mainWindowWillClose(_ notification: Notification) {
    mainWindowClosed = true
    DispatchQueue.main.async { NSApp.terminate(nil) }
  }

  override func applicationShouldTerminate(_ sender: NSApplication) -> NSApplication.TerminateReply {
    guard !mainWindowClosed, let window = mainFlutterWindow else {
      return .terminateNow
    }
    if sender.isHidden {
      sender.unhide(nil)
    }
    if window.isMiniaturized {
      window.deminiaturize(nil)
    }
    window.makeKeyAndOrderFront(nil)
    DispatchQueue.main.async { window.performClose(nil) }
    return .terminateCancel
  }

  override func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool {
    return true
  }
}
