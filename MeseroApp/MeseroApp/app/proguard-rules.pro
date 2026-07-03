# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# Kotlin Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembernames class kotlinx.** { volatile <fields>; }

# Compose
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# App models — no ofuscar entidades Room
-keep class com.restaurante.mesero.data.local.entity.** { *; }
-keep class com.restaurante.mesero.data.local.dao.** { *; }

# Mantener enums
-keepclassmembers enum * { *; }

# Mantener nombres de Parcelable y Serializable
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# General
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable
-dontobfuscate
