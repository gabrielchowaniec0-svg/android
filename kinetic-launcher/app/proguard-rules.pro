# R8 rules for Kinetic's release build.
#
# Kinetic's own classes are kept whole (they're small, and some are created by
# Android from the manifest or looked up by name), while the libraries around them
# still get shrunk and optimised.
-keep class com.kinetic.** { *; }

# Kotlin metadata used by reflection-free code paths is fine to drop, but keep
# line numbers so crash reports stay readable.
-keepattributes SourceFile,LineNumberTable,*Annotation*,Signature,InnerClasses,EnclosingMethod
-renamesourcefileattribute SourceFile

# security-crypto pulls in Tink, which mentions optional annotation classes.
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn com.google.api.client.http.**
-dontwarn org.joda.time.**
-dontwarn com.google.crypto.tink.**
