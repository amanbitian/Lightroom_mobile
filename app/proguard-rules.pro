# R8 rules for LumaRoom.
# ML Kit and Compose ship their own consumer rules; nothing in the app uses reflection on its own classes.
# Keep line numbers so crash reports from release builds stay readable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
-keep class com.lightroom.mobile.NativeRaw { *; }
-keep class com.lightroom.mobile.RawFrame { *; }
