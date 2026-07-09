# Modelos serializados por Gson: conservar nombres de campos.
-keepclassmembers class com.eatfood.control.mobile.data.model.** { *; }
-keep class com.eatfood.control.mobile.data.model.** { *; }

# Entidades/DAOs de Room (cola offline): mismo criterio que los modelos de Gson,
# para no arriesgar que R8 renombre un campo que el código generado por KSP referencia.
-keepclassmembers class com.eatfood.control.mobile.data.db.** { *; }
-keep class com.eatfood.control.mobile.data.db.** { *; }

# Gson: hace reflexión sobre tipos genéricos (TypeToken) y anotaciones de campo.
-keepattributes Signature, InnerClasses, EnclosingMethod, RuntimeVisibleAnnotations, AnnotationDefault
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken

# Retrofit / OkHttp — reglas oficiales de Retrofit para R8 (square/retrofit#3315).
-dontwarn okhttp3.**
-dontwarn retrofit2.**
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-dontwarn org.codehaus.mojo.animal_sniffer.AnnotationStub
-dontwarn javax.annotation.**
-dontwarn kotlin.Unit
-dontwarn retrofit2.KotlinExtensions
-dontwarn retrofit2.KotlinExtensions$*
# Las funciones suspend de ApiService/ScanApiService se compilan a Continuation;
# con R8 en modo full las firmas genéricas se pierden si esta clase no se conserva.
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
-keep,allowobfuscation,allowshrinking class retrofit2.Response

# SDK ZKFinger (accedido por reflexión). No ofuscar si lo agregas.
-keep class com.zkteco.** { *; }
-keep class com.zkfinger.** { *; }
-dontwarn com.zkteco.**
-dontwarn com.zkfinger.**
