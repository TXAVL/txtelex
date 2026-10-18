# TxTelex — Bàn phím tiếng Việt tuỳ biến cho Android

Bàn phím Android gõ Telex/VNI với lõi bộ gõ từ [ptrinh/viettelex](https://github.com/ptrinh/viettelex) (MIT),
giao diện và hệ thống tuỳ biến viết mới. Package: `nm.txa.telex`.

## Build (Windows, không cần Android Studio)

```powershell
.\gradlew.bat testDebugUnitTest   # unit test (engine + tầng IME)
.\gradlew.bat assembleDebug       # APK: src\app\build\outputs\apk\debug\app-debug.apk
```

Yêu cầu: JDK 17, Android SDK (platform 35, build-tools 35.0.0), `local.properties` trỏ `sdk.dir`.
Lõi tham chiếu: ptrinh/viettelex (không kèm trong repo).

## Giấy phép

- Lõi bộ gõ (`engine/`): MIT — Copyright (c) 2026 Phil Trinh.
- Mô hình gõ vuốt `futoswipe.bin`: FUTO Model Weights License 1.0 — Powered by FUTO Swipe.
