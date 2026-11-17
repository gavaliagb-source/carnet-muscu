# Runelift montre : on garde tout notre code, on retire seulement ce qui ne sert pas dans les bibliothèques.
-keep class app.runelift.wear.** { *; }
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn org.checkerframework.**
-dontwarn com.google.common.**
-ignorewarnings
