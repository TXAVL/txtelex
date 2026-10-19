@echo off
rem Build nhanh TxTelex: build.cmd [debug^|release^|aab^|all^|test^|install^|run^|clean^|key-password] [debug^|release]
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0build.ps1" %*
