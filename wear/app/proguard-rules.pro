# kotlinx.serialization keeps its generated serializers on the companion of each
# @Serializable class; R8 needs telling. This covers the generated protocol models and the
# app's own serializable types (the pairing response).
-keepclassmembers class dev.claudewear.** {
    *** Companion;
}
-keepclasseswithmembers class dev.claudewear.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class dev.claudewear.**$$serializer { *; }
