# NovaBlock

NovaBlock is an Android WebView launcher for browser-based Eaglercraft clients. Version **0.3.0** includes a redesigned launcher screen, verified version shortcuts, graphics/WebAssembly checks, and an offline-client HTML importer.

## What is included

- Fullscreen Android WebView with hardware acceleration and DOM storage.
- Online launch links for Eaglercraft versions found in the public catalog: 1.8.8, 1.12.2, 26.2, 26.3, and 1.5.2.
- Local import for a self-contained offline `.html` / `.htm` client. ZIP packages must be extracted first.
- Native Android file chooser support for uploads requested by a game client (for example, resource-pack import).
- WebGL 1 / WebGL 2 / WebAssembly diagnostics, a settings page, reduced-motion toggle, version persistence, and a neon NovaBlock app icon.
- Back navigation returns from a launched game/client to the launcher.

## Important runtime limitation

This project is an **Eaglercraft browser-client launcher**, not a full Minecraft Java Edition launcher. It does not include a JVM or Mojang game files. Eaglercraft builds use browser technologies such as JavaScript/WebAssembly and a WebGL rendering layer. The selected online client is opened at the public Eaglercraft site; WASM-GC selection is handled by the client/browser compatibility logic rather than pretending that a JVM is bundled here.

Standard Java Edition versions such as 1.8.9, 1.16.5, 1.20.4, and 1.21.11 are not advertised as supported unless a compatible client/runtime is supplied. Only use clients and assets you have permission to use.

## Build the APK

The GitHub Actions workflow `.github/workflows/android-apk.yml` checks the project, validates the HTML launcher script, builds the debug APK, verifies the APK archive/signature, and uploads the installable file with a checksum and installation note.

To build locally from a machine with Android SDK installed:

```bash
cd android
gradle :app:assembleDebug
```

Output:

`android/app/build/outputs/apk/debug/app-debug.apk`

The debug APK is installable on Android 7.0+ (API 24+). A debug-signed APK is intended for testing; updates signed with a different debug key may need the previous installation to be removed first.

## Repository layout

- `android/` — Android WebView wrapper and APK build.
- `android/app/src/main/assets/index.html` — NovaBlock launcher UI.
- `versions.json` — verified browser-client catalog and unsupported-version notes.

