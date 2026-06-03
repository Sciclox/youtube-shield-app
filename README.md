# 🛡️ YouTube Shield

[![Build Android APK](https://github.com/Sciclox/youtube-shield-app/actions/workflows/android.yml/badge.svg)](https://github.com/Sciclox/youtube-shield-app/actions/workflows/android.yml)
![Platform](https://img.shields.io/badge/Platform-Android-green.svg?style=flat-square)
![Language](https://img.shields.io/badge/Language-Kotlin-purple.svg?style=flat-square)
![SDK](https://img.shields.io/badge/Min%20SDK-24%20%28Android%207.0%29-blue.svg?style=flat-square)

**YouTube Shield** es una aplicación móvil nativa para Android desarrollada en Kotlin. Envuelve la versión móvil de YouTube (`m.youtube.com`) en un contenedor web de alto rendimiento e integra una solución de bloqueo de publicidad multicapa inspirada en la tecnología de **Brave Shields**. 

Evita entrar a navegadores o lidiar con molesta publicidad visual y de video en tu dispositivo móvil.

---

## ✨ Características Principales

*   🚫 **Bloqueo Multicapa de Anuncios:** Intercepta peticiones de red a servidores de anuncios y limpia la interfaz de banners.
*   ⚡ **Omisión Inteligente de Videos:** Salta automáticamente los anuncios de video (pre-roll/mid-roll), silenciando y acelerando su reproducción a 16x de manera inmediata cuando no son omitibles.
*   🎧 **Reproducción en Segundo Plano Total:** La aplicación utiliza un **Servicio en Primer Plano (Foreground Service)** para mantener vivo el proceso de reproducción y evitar que el sistema de Android lo pause o destruya al minimizar la app o apagar la pantalla.
*   🎛️ **Widget de Notificación Multimedia (MediaSession):** Controla el video en reproducción desde la cortina de notificaciones o la pantalla de bloqueo (Retroceder, Avanzar, Reproducir/Pausar, y botón de Bucle/Loop para repetir la canción infinitamente). Muestra en tiempo real el título de la canción activa y el icono de la app.
*   🎨 **Icono Original Neón Cyberpunk:** Diseño moderno con degradados neón (violeta a cian) en forma de escudo con un botón de play ardiente (naranja a amarillo) y efecto cristalino.
*   📱 **Modo Horizontal Inmersivo Nativo:** Ajusta automáticamente la pantalla a modo horizontal (Landscape) y oculta las barras del sistema cuando se entra en pantalla completa.
*   🧭 **Barra de Navegación Flotante Premium:** Controles minimalistas flotantes (Atrás, Adelante, Inicio, Recargar) integrados estéticamente.
*   🛡️ **Interruptor Dinámico de Escudo:** Activa o desactiva el bloqueo de anuncios en caliente con un botón interactivo que recuerda tu preferencia.
*   ☁️ **Compilación Automatizada (CI/CD):** Compilado automáticamente en la nube con GitHub Actions.

---

## ⚙️ ¿Cómo funciona el Bloqueo de Anuncios?

El bloqueo se implementa en tres capas fundamentales para ofrecer una navegación completamente libre de anuncios:

1.  **Filtro de Red (`shouldInterceptRequest`):**  
    La aplicación carga una base de datos local en memoria (`hosts.txt` en la carpeta `assets`). Antes de que el WebView realice cualquier solicitud DNS (por ejemplo, a `doubleclick.net`), el motor de la app la intercepta y devuelve un recurso vacío (`200 OK` sin contenido), previniendo la descarga de anuncios gráficos y telemetría de rastreo.
2.  **Limpieza del DOM (CSS Inyectado):**  
    Oculta los contenedores vacíos o banners promocionales de la versión móvil aplicando hojas de estilo inyectadas en tiempo de ejecución:
    ```css
    #masthead-ad, .ad-container, .ad-div, .video-ads, .ytp-ad-module { 
        display: none !important; 
    }
    ```
3.  **Acelerador y Omitidor de Anuncios (JavaScript Dinámico):**  
    Un script en segundo plano se ejecuta periódicamente dentro del WebView:
    *   Detecta instantáneamente el botón `Saltar anuncio` (Skip Ad) y lo presiona.
    *   Si se muestra un anuncio no omitible, silencia el elemento `<video>` de publicidad y acelera su velocidad de reproducción a `16.0x` mientras adelanta el cabezal de reproducción al final para saltarlo en menos de un segundo.

---

## 🚀 Instalación Directa (Sin Instalar Android Studio)

No es necesario compilar el código de manera local. Puedes descargar el archivo `.apk` directamente desde GitHub:

1.  Ve a la pestaña [**Actions**](https://github.com/Sciclox/youtube-shield-app/actions) de este repositorio.
2.  Selecciona la última ejecución del flujo de trabajo **Build Android APK** (marcada con un check verde ✅).
3.  Baja hasta la sección **Artifacts** en la parte inferior y haz clic en **`youtube-shield-debug-apk`**.
4.  Descomprime el archivo ZIP descargado en tu teléfono e instala el archivo `.apk` resultante.

---

## 🛠️ Compilación y Desarrollo Local

Si deseas modificar el código fuente o compilar el proyecto tú mismo:

### Requisitos previos:
*   **Android Studio** (Hedgehog o superior recomendado)
*   **Android SDK 34** (Target SDK)
*   **JDK 17**

### Pasos para compilar:
1.  Clona el repositorio:
    ```bash
    git clone https://github.com/Sciclox/youtube-shield-app.git
    ```
2.  Abre la carpeta del proyecto en Android Studio.
3.  Espera a que Gradle sincronice las dependencias del proyecto.
4.  Conecta tu dispositivo móvil con la **Depuración USB** activada.
5.  Haz clic en **Run** (`Shift + F10`) o ve a `Build > Build Bundle(s) / APK(s) > Build APK(s)` para generar el ejecutable localmente.

---

## 📁 Estructura del Código

*   `app/src/main/java/com/example/youtubeshield/MainActivity.kt`: Lógica de navegación del WebView, inyección de scripts, persistencia de estados y soporte fullscreen.
*   `app/src/main/java/com/example/youtubeshield/AdBlocker.kt`: Motor de análisis de subdominios y bloqueo por DNS.
*   `app/src/main/assets/hosts.txt`: Listado de firmas y hostnames de publicidad bloqueados.
*   `app/src/main/res/layout/activity_main.xml`: Interfaz de usuario (Layout) optimizada.
