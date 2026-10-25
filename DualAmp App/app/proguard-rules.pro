# JNI entry points are looked up by name from libmilkbridge.so.
-keep class com.dualamp.viz.MilkNative { *; }

# jaudiotagger (Edit song info): creates its tag frame classes by name, and logs through java.util.logging.
-keep class org.jaudiotagger.** { *; }
-dontwarn org.jaudiotagger.**
-dontwarn java.awt.**
-dontwarn javax.imageio.**
-dontwarn javax.swing.**
