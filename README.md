# Meta Business Bot — Auto-respuesta con IA local

App nativa Android (Kotlin) que intercepta notificaciones de mensajes de **Instagram, Messenger (Página de Facebook) y WhatsApp Business**, procesa el mensaje con un LLM local (**Gemma 3 1B** vía MediaPipe) y responde mediante el *inline reply* nativo de la notificación, sin abrir las apps. Las preguntas de precio/stock se derivan con respuesta instantánea al catálogo de WhatsApp, sin pasar por el LLM.

> **Por qué no se usa Meta Business Suite como canal único**: verificado en dispositivo real
> (`dumpsys notification`), las notificaciones de `com.facebook.pages.app` **no exponen
> acción de respuesta con RemoteInput** (solo el intent de abrir la app), así que el
> inline reply es imposible por esa vía. Las apps individuales de cada canal sí la
> exponen; Business Suite se mantiene como target solo para registrar en el log lo
> que llega por ahí.

## Arquitectura

```
Notificación Instagram / Messenger / WhatsApp Business
        │
        ▼
WhatsAppNotificationListener  (NotificationListenerService, hilo principal)
        │  filtra los paquetes objetivo, extrae remitente + texto (MessagingStyle)
        │  canal por paquete (com.instagram.android → INSTAGRAM, etc.)
        ▼
serviceScope.launch { ... }   (corrutina en Dispatchers.Default)
        │
        ▼
LocalAiProcessor (interfaz)  ←  MediaPipeAiProcessor (Gemma 3 1B, .task local)
        │                        └─ fallback: MockAiProcessor si falta el modelo
        │                        └─ atajo: preguntas de precio/stock → enlace al
        │                           catálogo wa.me, sin inferencia
        ▼
ReplySender                   busca la Action con RemoteInput y dispara su
                              PendingIntent con el texto → la app de origen envía
```

Ficheros clave:

| Fichero | Rol |
|---|---|
| `service/WhatsAppNotificationListener.kt` | Escucha, filtra, deduplica y detecta el canal (Instagram/Facebook/WhatsApp) |
| `reply/ReplySender.kt` | Inyección de la respuesta vía RemoteInput |
| `ai/LocalAiProcessor.kt` | Interfaz del motor de IA + guía de integración |
| `ai/MediaPipeAiProcessor.kt` | Motor real: Gemma 3 1B (.task) + atajo al catálogo |
| `ai/MockAiProcessor.kt` | Implementación de prueba (respuesta enlatada) |
| `model/IncomingMessage.kt` | Mensaje normalizado + enum `Channel` |
| `MainActivity.kt` | Activar acceso a notificaciones y exención de batería |

## Puesta en marcha

1. Copiar `secrets.properties.example` a `secrets.properties` (no versionado) y completar los datos del negocio: nombre, dueña, ubicación y URL del catálogo de WhatsApp. Sin este fichero la app compila con valores genéricos y el atajo de catálogo queda desactivado.
2. Compilar con `./gradlew assembleDebug` (o abrir en Android Studio). Instalar con `adb install -r app/build/outputs/apk/debug/app-debug.apk`.
3. Ejecutar en un dispositivo real con las apps del canal a cubrir instaladas y con sesión de la cuenta business: **Instagram** (`com.instagram.android`), **Messenger** (`com.facebook.orca`, con el perfil de la Página) y/o **WhatsApp Business** (`com.whatsapp.w4b`). Notificaciones activadas en cada una.
4. En la app: pulsar **Conceder acceso a notificaciones** y activar "WhatsApp Bot".
5. (Recomendado) Pulsar **Eximir de optimización de batería**. En Honor/Xiaomi/Oppo, además: Ajustes > Batería > Inicio de aplicaciones → gestión manual con las tres opciones activadas.
6. Enviar un mensaje al Instagram/Página/WhatsApp Business desde otra cuenta: llegará la respuesta automática, etiquetada con el canal detectado. No se responde a grupos por seguridad.
7. Hay un espaciado forzado de 3-5 s entre envíos para evitar ráfagas, y protección anti-bucle (ignora los mensajes propios).

Para escuchar también WhatsApp personal, descomentar `com.whatsapp` en `TARGET_PACKAGES` (`WhatsAppNotificationListener.kt`).

## Persistencia en segundo plano

- El `NotificationListenerService` lo enlaza y mantiene vivo el propio sistema mientras el acceso a notificaciones esté concedido; **no** usa `startForeground()`.
- `onListenerDisconnected()` llama a `requestRebind()` para recuperarse si el sistema desconecta el listener.
- En OEMs agresivos (Xiaomi/MIUI, Huawei, Oppo, Samsung), además de la exención de batería, hay que excluir la app de "autostart"/"apps en suspensión" en los ajustes propios del fabricante.
- Si el listener deja de recibir eventos tras una actualización de la app, desactivar y reactivar el acceso a notificaciones fuerza el re-enlace.

## LLM local (Gemma 3 1B vía MediaPipe)

`MediaPipeAiProcessor` usa **Gemma 3 1B IT q4** (`.task`, ~555MB) con `com.google.mediapipe:tasks-genai`. El modelo no va en el APK: descargarlo de [litert-community/Gemma3-1B-IT](https://huggingface.co/litert-community/Gemma3-1B-IT) (requiere aceptar la licencia de Gemma) y subirlo con:

```bash
adb shell mkdir -p /data/local/tmp/llm
adb push Gemma3-1B-IT_multi-prefill-seq_q4_ekv2048.task /data/local/tmp/llm/gemma3-1b-q4.task
adb shell chmod 644 /data/local/tmp/llm/gemma3-1b-q4.task
```

- Solo se aceptan `.task` con "gemma" en el nombre (la plantilla de prompt es la de Gemma); si hay varios gana el más reciente. Sin modelo → cae al `MockAiProcessor`.
- Preguntas con precio/stock/catálogo/productos se responden al instante con el enlace del catálogo (`CATALOG_URL` en `MediaPipeAiProcessor.kt`), sin inferencia.
- Rendimiento medido en Honor X7B (Helio G85): ~33s la primera respuesta (carga del modelo) y ~22s las siguientes.
- El prompt del negocio prohíbe inventar precios/stock y limita a 1-2 frases; además hay truncado duro a 2 frases / 300 caracteres.

## Avisos

- La auto-respuesta automatizada puede violar las Condiciones del Servicio de Meta; usar en cuenta propia y bajo tu responsabilidad. Para un bot oficial multi-canal existe la Meta Business Platform (webhooks + Graph API), que requiere un servidor.
- La detección por notificaciones solo ve mensajes **mientras la notificación existe** y el chat no está silenciado ni abierto.
- Si Instagram/Messenger y Business Suite notifican el mismo mensaje, puede procesarse dos veces en el log, pero solo la app individual puede responder (Business Suite no expone RemoteInput).
