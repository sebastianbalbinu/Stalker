# Stalker: se optimiza y se recorta, pero sin cambiar nombres (errores legibles y nada que dependa de nombres se rompe).
-dontobfuscate

# Librerías que mencionan clases opcionales que no están en Android.
-dontwarn javax.annotation.**
-dontwarn org.checkerframework.**
-dontwarn com.google.errorprone.**
-dontwarn com.google.j2objc.**
-dontwarn sun.misc.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-ignorewarnings

# Lo que Android crea por su nombre (servicio, receptores) ya lo protege el manifiesto;
# los enums se guardan por nombre en los ajustes.
-keepclassmembers enum com.sebas.stalker.** { *; }
