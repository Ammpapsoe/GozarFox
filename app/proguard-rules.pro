-keep class org.amnezia.awg.** { *; }
-keep class com.google.zxing.** { *; }
-dontwarn org.amnezia.awg.**

-keep class com.gozar.** { *; }
-keep class org.torproject.** { *; }
-keep class net.freehaven.** { *; }
-keep class IPtProxy.** { *; }
-keep class go.** { *; }
-dontwarn IPtProxy.**
-dontwarn go.**

-keep public class * extends androidx.fragment.app.Fragment
-keep public class * extends androidx.recyclerview.widget.RecyclerView

-keepattributes *Annotation*
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
