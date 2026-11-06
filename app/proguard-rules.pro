# Pleos SDK 와 그 음성 엔진은 JNA·리플렉션으로 불린다. 줄이면 차량에서만 터지므로 통째로 둔다.
-keep class ai.pleos.** { *; }
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { *; }
-dontwarn ai.pleos.**
-dontwarn com.sun.jna.**
-dontwarn java.awt.**

# 저장 파일(kotlinx.serialization)
-keepattributes *Annotation*, InnerClasses, Signature
-keepclassmembers @kotlinx.serialization.Serializable class com.cihmine.carvoicegame.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep class com.cihmine.carvoicegame.**$$serializer { *; }

# Pleos SDK 가 끌고 오는 암호화 라이브러리(리플렉션·프로토콜 버퍼)
-keep class com.google.crypto.tink.** { *; }
-keep class com.google.protobuf.** { *; }
-dontwarn javax.annotation.**
-dontwarn com.google.api.client.**
-dontwarn org.joda.time.**
