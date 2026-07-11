@echo off
setlocal
title ControlEatFood

:: Correccion: establecer la politica de ejecucion para el usuario actual
powershell -Command "Set-ExecutionPolicy RemoteSigned -Scope CurrentUser -Force"

:: Lanzador delgado: toda la logica vive en Inicio.ps1 (Pruebas/Produccion).
:: Se usa -ExecutionPolicy Bypass para evitar el bloqueo de scripts al
:: hacer doble clic; Inicio.ps1 se auto-eleva solo si eliges Produccion.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0Inicio.ps1"
