# WhoCaller release rules. Most libraries ship their own consumer rules; these cover reflection we rely on.

# kotlinx.serialization: keep generated serializers for DTOs.
-keepattributes *Annotation*, InnerClasses, Signature, Exceptions
-dontnote kotlinx.serialization.**
-keep,includedescriptorclasses class com.rskusum.whocaller.**$$serializer { *; }
-keepclassmembers class com.rskusum.whocaller.** {
    *** Companion;
}
-keepclasseswithmembers class com.rskusum.whocaller.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Retrofit service interfaces are created by reflection.
-keep,allowobfuscation,allowshrinking interface com.rskusum.whocaller.core.network.WhoCallerApi
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
-if interface * { @retrofit2.http.* <methods>; }
-keep,allowobfuscation interface <1>

# Enums stored by name in Room/DataStore must keep their names.
-keepclassmembers enum com.rskusum.whocaller.core.model.** {
    <fields>;
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Crash reports: keep line numbers, hide original source file names.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Never log in release.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
