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

# netty 4.1.140 performs Runtime reflection in class initialisation:
#   AbstractByteBufAllocator.<clinit> -> ResourceLeakDetector.addExclusions(class, "toLeakAwareBuffer")
#   AdvancedLeakAwareByteBuf.<clinit> -> addExclusions(class, "touch", "recordLeakNonRefCountingOperation")
#   ReferenceCountUtil.<clinit>       -> addExclusions(class, "touch")
# addExclusions() walks clz.getDeclaredMethods() and throws
# IllegalArgumentException("Can't find '[x]' in <clz>") when R8 has renamed
# the method. That exception kills the worker thread on Connect with
# ExceptionInInitializerError ("Send feedback" crash). Keep the exact
# classes whose members are looked up this way.
-keep class io.netty.util.ReferenceCountUtil { *; }
-keep class io.netty.buffer.AbstractByteBufAllocator { *; }
-keep class io.netty.buffer.AdvancedLeakAwareByteBuf { *; }
