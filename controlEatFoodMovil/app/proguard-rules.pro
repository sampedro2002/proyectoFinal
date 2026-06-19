# Modelos serializados por Gson: conservar nombres de campos.
-keepclassmembers class com.eatfood.control.mobile.data.model.** { *; }
-keep class com.eatfood.control.mobile.data.model.** { *; }

# Retrofit / OkHttp
-dontwarn okhttp3.**
-dontwarn retrofit2.**
-keepattributes Signature, InnerClasses, EnclosingMethod

# SDK ZKFinger (accedido por reflexión). No ofuscar si lo agregas.
-keep class com.zkteco.** { *; }
-keep class com.zkfinger.** { *; }
-dontwarn com.zkteco.**
-dontwarn com.zkfinger.**
