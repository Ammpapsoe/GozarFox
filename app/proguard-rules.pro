-keep class com.gozar.FoxNative { *; }
-keepclassmembers class com.gozar.FoxNative { *; }
-keepattributes *Annotation*
-dontwarn org.bouncycastle.**

# hev-socks5-tunnel: JNI_OnLoad resolves FindClass("hev/htproxy/TProxyService")
# and RegisterNatives by exact name; renaming or stripping breaks the engine.
-keep class hev.htproxy.TProxyService { *; }
-keepclasseswithmembernames class hev.htproxy.TProxyService { native <methods>; }

# dnsjava ships @lombok.Generated on its own generated members; lombok is not
# on the classpath. The classes below are optional integrations of dnsjava
# and netty (not present); referenced from code paths we do not use.
-dontwarn lombok.Generated
-dontwarn org.apache.log4j.**
-dontwarn org.apache.logging.log4j.**
-dontwarn org.eclipse.jetty.alpn.**
-dontwarn org.eclipse.jetty.npn.**
-dontwarn org.slf4j.impl.StaticLoggerBinder
-dontwarn org.slf4j.impl.StaticMDCBinder
-dontwarn org.slf4j.impl.StaticMarkerBinder
