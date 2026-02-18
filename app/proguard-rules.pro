# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# Keep custom icons
-keep class social.mycelium.android.ui.icons.** { *; }

# Optimize Material Icons - only keep used icons to reduce APK size
# Remove unused icons to improve build times and reduce APK size
-keep class androidx.compose.material.icons.Icons$Default {
    public static androidx.compose.ui.graphics.vector.ImageVector Home;
    public static androidx.compose.ui.graphics.vector.ImageVector Router;
    public static androidx.compose.ui.graphics.vector.ImageVector Email;
    public static androidx.compose.ui.graphics.vector.ImageVector Notifications;
    public static androidx.compose.ui.graphics.vector.ImageVector AccountBalanceWallet;
    public static androidx.compose.ui.graphics.vector.ImageVector Star;
    public static androidx.compose.ui.graphics.vector.ImageVector Favorite;
    public static androidx.compose.ui.graphics.vector.ImageVector Reply;
    public static androidx.compose.ui.graphics.vector.ImageVector PersonAdd;
    public static androidx.compose.ui.graphics.vector.ImageVector MoreVert;
    public static androidx.compose.ui.graphics.vector.ImageVector Share;
    public static androidx.compose.ui.graphics.vector.ImageVector Report;
    public static androidx.compose.ui.graphics.vector.ImageVector Add;
    public static androidx.compose.ui.graphics.vector.ImageVector Clear;
    public static androidx.compose.ui.graphics.vector.ImageVector KeyboardArrowDown;
    public static androidx.compose.ui.graphics.vector.ImageVector Menu;
    public static androidx.compose.ui.graphics.vector.ImageVector FilterList;
    public static androidx.compose.ui.graphics.vector.ImageVector Person;
    public static androidx.compose.ui.graphics.vector.ImageVector Settings;
    public static androidx.compose.ui.graphics.vector.ImageVector Info;
    public static androidx.compose.ui.graphics.vector.ImageVector Close;
    public static androidx.compose.ui.graphics.vector.ImageVector Publish;
    public static androidx.compose.ui.graphics.vector.ImageVector Refresh;
    public static androidx.compose.ui.graphics.vector.ImageVector Wifi;
    public static androidx.compose.ui.graphics.vector.ImageVector Delete;
    public static androidx.compose.ui.graphics.vector.ImageVector ChevronRight;
    public static androidx.compose.ui.graphics.vector.ImageVector History;
    public static androidx.compose.ui.graphics.vector.ImageVector NorthWest;
    public static androidx.compose.ui.graphics.vector.ImageVector SearchOff;
    public static androidx.compose.ui.graphics.vector.ImageVector KeyboardArrowUp;
    public static androidx.compose.ui.graphics.vector.ImageVector Comment;
    public static androidx.compose.ui.graphics.vector.ImageVector List;
    public static androidx.compose.ui.graphics.vector.ImageVector Palette;
    public static androidx.compose.ui.graphics.vector.ImageVector ColorLens;
    public static androidx.compose.ui.graphics.vector.ImageVector Tune;
}

-keep class androidx.compose.material.icons.Icons$Outlined {
    public static androidx.compose.ui.graphics.vector.ImageVector ArrowUpward;
    public static androidx.compose.ui.graphics.vector.ImageVector ArrowDownward;
    public static androidx.compose.ui.graphics.vector.ImageVector Bookmark;
    public static androidx.compose.ui.graphics.vector.ImageVector Reply;
    public static androidx.compose.ui.graphics.vector.ImageVector Bolt;
    public static androidx.compose.ui.graphics.vector.ImageVector ChatBubble;
    public static androidx.compose.ui.graphics.vector.ImageVector ChatBubbleOutline;
    public static androidx.compose.ui.graphics.vector.ImageVector AlternateEmail;
    public static androidx.compose.ui.graphics.vector.ImageVector Home;
    public static androidx.compose.ui.graphics.vector.ImageVector TrendingUp;
    public static androidx.compose.ui.graphics.vector.ImageVector Schedule;
    public static androidx.compose.ui.graphics.vector.ImageVector Login;
    public static androidx.compose.ui.graphics.vector.ImageVector Settings;
    public static androidx.compose.ui.graphics.vector.ImageVector Edit;
    public static androidx.compose.ui.graphics.vector.ImageVector Notifications;
    public static androidx.compose.ui.graphics.vector.ImageVector Person;
    public static androidx.compose.ui.graphics.vector.ImageVector Lock;
    public static androidx.compose.ui.graphics.vector.ImageVector FavoriteBorder;
    public static androidx.compose.ui.graphics.vector.ImageVector BugReport;
    public static androidx.compose.ui.graphics.vector.ImageVector Info;
    public static androidx.compose.ui.graphics.vector.ImageVector Router;
    public static androidx.compose.ui.graphics.vector.ImageVector Publish;
    public static androidx.compose.ui.graphics.vector.ImageVector Refresh;
    public static androidx.compose.ui.graphics.vector.ImageVector Palette;
    public static androidx.compose.ui.graphics.vector.ImageVector ColorLens;
    public static androidx.compose.ui.graphics.vector.ImageVector Tune;
    public static androidx.compose.ui.graphics.vector.ImageVector Campaign;
    public static androidx.compose.ui.graphics.vector.ImageVector Language;
}

-keep class androidx.compose.material.icons.Icons$Filled {
    public static androidx.compose.ui.graphics.vector.ImageVector ChevronRight;
    public static androidx.compose.ui.graphics.vector.ImageVector List;
}

-keep class androidx.compose.material.icons.Icons$AutoMirrored$Filled {
    public static androidx.compose.ui.graphics.vector.ImageVector ArrowBack;
    public static androidx.compose.ui.graphics.vector.ImageVector List;
}

# Keep vector graphics classes
-keep class androidx.compose.ui.graphics.vector.** { *; }

# Optimization flags for better performance
-optimizations !code/simplification/arithmetic,!code/simplification/cast,!field/*,!class/merging/*
-optimizationpasses 5
-allowaccessmodification
-dontpreverify

# Keep data classes with @Immutable annotation
-keep @androidx.compose.runtime.Immutable class * { *; }
-keep @androidx.compose.runtime.Stable class * { *; }

# Keep Kotlin metadata for reflection
-keep class kotlin.Metadata { *; }

# Keep ViewModel classes
-keep class * extends androidx.lifecycle.ViewModel { *; }

# Ktor optimizations
-keep class io.ktor.** { *; }
-keepclassmembers class io.ktor.** { *; }
-dontwarn io.ktor.**

# Kotlinx serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

-keep,includedescriptorclasses class social.mycelium.android.**$$serializer { *; }
-keepclassmembers class social.mycelium.android.** {
    *** Companion;
}
-keepclasseswithmembers class social.mycelium.android.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Remove logging in release builds for better performance
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}

# Compose specific optimizations
-dontwarn androidx.compose.**
-keep class androidx.compose.runtime.** { *; }
-keep class androidx.compose.ui.** { *; }
