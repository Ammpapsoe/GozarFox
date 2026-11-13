import Cocoa
import FlutterMacOS
import Network
import WebKit

class MainFlutterWindow: NSWindow {
  override func awakeFromNib() {
    let flutterViewController = FlutterViewController()
    let windowFrame = self.frame
    self.contentViewController = flutterViewController
    self.setFrame(windowFrame, display: true)
    self.contentMinSize = NSSize(width: 360, height: 360)

    // The browser sign-in's web view proxy (lib/web_signin.dart).
    let webviewChannel = FlutterMethodChannel(
      name: "gozar/webview",
      binaryMessenger: flutterViewController.engine.binaryMessenger)
    webviewChannel.setMethodCallHandler { call, result in
      switch call.method {
      case "setWebViewProxy":
        let args = call.arguments as? [String: Any]
        result(MainFlutterWindow.setWebViewProxy(args?["address"] as? String))
      default:
        result(FlutterMethodNotImplemented)
      }
    }

    RegisterGeneratedPlugins(registry: flutterViewController)

    super.awakeFromNib()
  }

  /// The sign-in web view goes through the core's sign-in proxy (an
  /// address, "127.0.0.1:port"), or straight out again when it is nil. It
  /// is set on the default website data store, which the app's web views
  /// use, for the connections they start from then on. It needs macOS 14;
  /// earlier, it answers false and the view connects by itself.
  private static func setWebViewProxy(_ address: String?) -> Bool {
    guard #available(macOS 14.0, *) else { return false }
    let store = WKWebsiteDataStore.default()
    guard let address = address, !address.isEmpty else {
      store.proxyConfigurations = []
      return true
    }
    guard let colon = address.lastIndex(of: ":"),
      let number = UInt16(address[address.index(after: colon)...]),
      let port = NWEndpoint.Port(rawValue: number)
    else { return false }
    let host = NWEndpoint.Host(String(address[..<colon]))
    store.proxyConfigurations = [
      ProxyConfiguration(httpCONNECTProxy: .hostPort(host: host, port: port))
    ]
    return true
  }
}
