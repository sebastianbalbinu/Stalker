# Stalker

Reproductor de música FLAC para Android, con estética de sala roja.

- Motor: Media3 (ExoPlayer) con salida en PCM flotante, así que los FLAC de 24 bits no se recortan a 16.
- Salida USB (Android 14+):
  - **Bit-perfect**: el audio llega al DAC sin tocar, si el teléfono lo permite.
  - **Hi-Res**: el DAC trabaja a la frecuencia original de cada canción, sin remuestreo.
  - **Normal**: pasa por el mezclador de Android.
- Lee la frecuencia y los bits reales del encabezado de cada FLAC.
- Ecualizador con presets (se apaga en Bit-perfect).
- Reproducción en segundo plano con controles en la notificación y la pantalla de bloqueo.

## Compilar

Cada push a `main` compila la APK con GitHub Actions y la publica en **Releases** como `Stalker.apk`.

## Instalar

1. En el celular, abre la página de Releases del repositorio y descarga `Stalker.apk`.
2. Ábrela y permite "instalar apps desconocidas" para tu navegador si Android lo pide.
3. Dale permiso de música y notificaciones al abrir Stalker.

Las actualizaciones se instalan encima sin perder nada (todas las versiones van firmadas con la misma llave).
