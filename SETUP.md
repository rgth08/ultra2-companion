# Ultra2 Companion — Guía de instalación

## Paso 1 — Instala Android Studio
https://developer.android.com/studio
(Windows/Mac/Linux — solo una vez, ~1GB)

## Paso 2 — Crea proyecto nuevo
1. Abre Android Studio → "New Project"
2. Selecciona "Empty Views Activity"
3. Nombre: `Ultra2Companion`
4. Package: `com.ultra2.companion`
5. Language: **Kotlin**
6. Min SDK: **API 26 (Android 8.0)**
7. Finish → espera que Gradle sincronice (~2 min)

## Paso 3 — Reemplaza los archivos generados

Copia estos archivos en las rutas indicadas dentro de tu proyecto:

```
app/src/main/java/com/ultra2/companion/
    ├── MainActivity.kt         ← reemplaza el existente
    ├── BleManager.kt           ← archivo nuevo
    └── FitProProtocol.kt       ← archivo nuevo

app/src/main/res/layout/
    └── activity_main.xml       ← reemplaza el existente

app/src/main/res/values/
    └── arrays.xml              ← archivo nuevo

app/src/main/res/drawable/
    ├── panel_bg.xml            ← archivo nuevo
    ├── dot_connected.xml       ← archivo nuevo
    ├── dot_disconnected.xml    ← archivo nuevo
    └── dot_scanning.xml        ← archivo nuevo

app/src/main/AndroidManifest.xml ← reemplaza el existente
app/build.gradle                 ← reemplaza el existente
```

## Paso 4 — Compila y envía al teléfono
1. Conecta tu Android por USB con depuración USB activada
2. En Android Studio: Run → Run 'app' (botón verde ▶)
3. Selecciona tu teléfono → OK
4. La app se instala directamente

---

## Alternativa sin PC — GitHub Actions (solo Termux + Git)

```bash
# En Termux:
pkg install git
cd /sdcard
git clone https://github.com/TU_USUARIO/ultra2-companion
# Sube los archivos al repo, GitHub Actions compila el APK
# Descarga el APK desde la pestaña "Actions" en GitHub
```
Dime si quieres que genere el workflow de GitHub Actions también.
