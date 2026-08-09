# kotlinx.serialization keeps its generated serializers on the companion of each
# @Serializable class; R8 needs telling.
-keepclassmembers class dev.claudewear.protocol.** {
    *** Companion;
}
-keepclasseswithmembers class dev.claudewear.protocol.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class dev.claudewear.protocol.**$$serializer { *; }
