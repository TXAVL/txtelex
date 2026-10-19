@echo off
rem Cài toàn bộ công cụ build TxTelex (JDK 17, Android SDK, NDK, CMake) — chạy một lần.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0setup.ps1" %*
