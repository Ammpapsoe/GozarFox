// JNI bridge into the vendored hev-socks5-tunnel native library.
// Method names and signatures are bound by exact name in src/hev-jni.c
// (JNI_OnLoad -> RegisterNatives); do not rename or make them internal,
// Kotlin mangles internal member names and JNI_OnLoad would fail.
package hev.htproxy

object TProxyService {
    // Loaded lazily so JVM unit tests (which have no native library on the
    // library path) can touch this object safely: isNativeAvailable reports
    // false and every entry point degrades instead of crashing the JVM.
    private val nativeLoaded: Boolean = runCatching {
        System.loadLibrary("hev-socks5-tunnel")
        true
    }.getOrDefault(false)

    val isNativeAvailable: Boolean get() = nativeLoaded

    external fun TProxyStartService(configPath: String, fd: Int): Boolean
    external fun TProxyStopService(): Boolean
    external fun TProxyIsRunning(): Boolean
    external fun TProxyGetStats(): LongArray
}
