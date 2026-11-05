# Mi Ciclo

App Android de seguimiento del ciclo menstrual. Funciona sin internet y guarda los datos solo en el teléfono.

## Generar la APK con GitHub (sin instalar nada)

1. Crea una cuenta gratis en github.com y un repositorio **privado** nuevo.
2. Elige "uploading an existing file" y sube el **contenido** de esta carpeta, incluida `.github`. Si tu navegador oculta esa carpeta, créala a mano con "Add file > Create new file", usando la ruta `.github/workflows/build-apk.yml`.
3. Abre la pestaña **Actions** y espera a que "Compilar APK" termine en verde (unos minutos).
4. Entra a esa ejecución y descarga **MiCiclo-APK**. Es un .zip con `app-release.apk` dentro.
5. Pasa la APK al teléfono, ábrela y permite "instalar apps desconocidas".

## Generar la APK con Android Studio

Abre esta carpeta en Android Studio, espera a que sincronice y usa
**Build > Build Bundle(s) / APK(s) > Build APK(s)** con la variante `release`
(Build > Select Build Variant). La APK queda en `app/build/outputs/apk/release/`.

## Firma

`app/miciclo.jks` es una clave para uso personal, y su contraseña está en `app/build.gradle`.
Usar siempre la misma permite actualizar la app sin perder los datos. Para publicar en
Play Store, crea una clave propia y privada.

## Actualizar el diseño o las funciones

Toda la interfaz y la lógica están en `app/src/main/assets/index.html`.
Si cambias la versión, sube `versionCode` en `app/build.gradle`.
