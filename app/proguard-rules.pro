# R8 keep rules for TidyLink.
#
# Wired up via proguardFiles() in app/build.gradle.kts - that is the only
# mechanism that reaches R8. Do not move these rules to a bare directory
# (e.g. src/main/keepRules/); AGP has no such convention and R8 will silently
# ignore the file, stripping serializers and breaking release builds only.
#
# R8 full mode is on by default (AGP 8.0+). Most libraries (Room, WorkManager,
# OkHttp) bundle their own consumer rules; what follows covers the gaps that
# bite in practice.

# --- Attributes needed by kotlinx-serialization ----------------------------
# Signature carries the generic types serializer lookup reads; the annotation
# attributes carry @Serializable itself.
-keepattributes Signature, InnerClasses, EnclosingMethod, RuntimeVisibleAnnotations, AnnotationDefault

# --- OkHttp ----------------------------------------------------------------
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**

# --- Bouncy Castle (X25519 for device sync, see sync/PairingCrypto.kt) -----
# bcprov bundles JDK-Provider integration classes referencing APIs Android
# doesn't have; TidyLink only ever uses the lightweight crypto.params/
# crypto.agreement API directly, never java.security's Provider registration,
# so those classes are unreachable at runtime - safe to silence, not keep.
-dontwarn org.bouncycastle.**

# --- kotlinx-serialization -------------------------------------------------
# The reified Json.decodeFromString<T>() / encodeToString<T>() used across the
# app resolve serializers REFLECTIVELY at runtime via serializer(typeOf<T>()).
# That lookup needs the generated $$serializer class and the Companion to
# survive shrinking; without these rules R8 full mode removes them and every
# decode throws SerializationException("Serializer for class 'X' is not
# found"). The chat wire types now pass ChatRequest.serializer() explicitly,
# which needs no reflection - but LinkEntity, LlmProvider, ProviderHealth and
# the trash/export model still go through the reified form, so these stay.
#
# The generic -if rules below are the upstream full-mode ruleset; the
# app-scoped rules that follow are belt-and-braces for dev.punit.tidylink.

# Keep the Companion of every @Serializable class.
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}

# Keep the serializer() factory on those Companions.
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep serializer() on @Serializable objects.
-if @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
}
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

# App types explicitly: the chat wire format, provider storage
# (LlmProvider/ProviderHealth), and the JSON export/import model.
-keep,includedescriptorclasses class dev.punit.tidylink.**$$serializer { *; }
-keepclassmembers class dev.punit.tidylink.** {
    *** Companion;
}
-keepclasseswithmembers class dev.punit.tidylink.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# --- WorkManager -----------------------------------------------------------
# Workers are instantiated reflectively via
# getDeclaredConstructor(Context, WorkerParameters), so the CONSTRUCTOR must be
# kept, not just the class (belt-and-braces: the androidx.work consumer rules
# normally cover this).
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# --- jsoup ------------------------------------------------------------------
-dontwarn org.jsoup.**
