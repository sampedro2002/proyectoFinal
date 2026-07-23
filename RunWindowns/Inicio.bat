@echo off
:: Elevar directamente como Administrador al hacer doble clic
:: Esto evita que el script se relance (y duplique ventanas) cuando se elige Produccion
powershell -NoProfile -ExecutionPolicy Bypass -Command "Start-Process powershell -Verb RunAs -ArgumentList @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', '%~dp0Inicio.ps1') -Wait"
