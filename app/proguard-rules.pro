# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.dzienisz.zureqshopper.**$$serializer { *; }
-keepclassmembers class com.dzienisz.zureqshopper.** { *** Companion; }
-keepclasseswithmembers class com.dzienisz.zureqshopper.** { kotlinx.serialization.KSerializer serializer(...); }
