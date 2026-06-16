# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# ---------------------------------------------------------------------------
# Stack traces – keep line numbers readable in crash reports
# ---------------------------------------------------------------------------
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ---------------------------------------------------------------------------
# Room – keep all entity and DAO classes so reflection-based DB access works
# ---------------------------------------------------------------------------
-keep class de.velospot.data.local.entity.** { *; }
-keep class de.velospot.data.local.dao.** { *; }
-keep class de.velospot.data.local.database.** { *; }

# ---------------------------------------------------------------------------
# Moshi – keep all DTO/model classes used for JSON (de)serialisation
# ---------------------------------------------------------------------------
-keep class de.velospot.data.remote.dto.** { *; }
-keepclassmembers class de.velospot.data.remote.dto.** { *; }
-keep @com.squareup.moshi.JsonClass class * { *; }
-keepclassmembers class * {
    @com.squareup.moshi.FromJson <methods>;
    @com.squareup.moshi.ToJson <methods>;
}
# Moshi Kotlin reflection adapter
-keep class kotlin.reflect.jvm.internal.** { *; }
-keep class kotlin.Metadata { *; }

# ---------------------------------------------------------------------------
# Retrofit – keep service interfaces and generic signatures
# ---------------------------------------------------------------------------
-keepattributes Signature
-keepattributes Exceptions
-keep interface de.velospot.data.remote.api.** { *; }
# Retrofit uses reflection on the response type; keep generic signatures
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}

# ---------------------------------------------------------------------------
# OkHttp / Okio
# ---------------------------------------------------------------------------
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.internal.** { *; }

# ---------------------------------------------------------------------------
# BRouter (btools.*) – keep all public API plus the private coordinate
# fields (ilat/ilon) that are read via reflection in BRouterEngine
# ---------------------------------------------------------------------------
-keep class btools.** { *; }
-keepclassmembers class btools.router.OsmPathElement {
    int ilat;
    int ilon;
}
-keepclassmembers class btools.router.OsmNodeNamed {
    int ilat;
    int ilon;
    java.lang.String name;
}
# Some BRouter classes reference desktop-only APIs (AWT / ImageIO) on code paths
# that are never reached on Android. These rules were previously appended by the
# F-Droid prebuild step; they now live here so the build needs no preparation.
-dontwarn java.awt.**
-dontwarn javax.imageio.**

# ---------------------------------------------------------------------------
# Hilt / Dagger – generated component classes must not be removed
# ---------------------------------------------------------------------------
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep @dagger.hilt.android.lifecycle.HiltViewModel class * { *; }
-keep @dagger.hilt.InstallIn class * { *; }

# ---------------------------------------------------------------------------
# Kotlin coroutines
# ---------------------------------------------------------------------------
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}
-dontwarn kotlinx.coroutines.**

# ---------------------------------------------------------------------------
# Domain / model classes (sealed classes, data classes used across layers)
# ---------------------------------------------------------------------------
-keep class de.velospot.domain.model.** { *; }
-keep class de.velospot.domain.repository.** { *; }
