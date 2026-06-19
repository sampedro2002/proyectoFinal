@echo off
title ControlEatFood - Development Startup Script
echo =====================================================================
echo           INICIANDO CONTROL EAT FOOD - ENTORNO DE DESARROLLO
echo =====================================================================
echo.

:: Base de datos ahora es MySQL local
echo [1/3] La base de datos configurada es MySQL (Asegurate de que el servicio MySQL este encendido)
echo.

:: Abrir terminal para el Backend
echo [2/3] Iniciando Servidor Backend (Spring Boot)...
start "Backend - Spring Boot" cmd /k "cd backend && mvn spring-boot:run"
echo.

:: Abrir terminal para el Frontend
echo [3/3] Iniciando Servidor Frontend (Vite)...
start "Frontend - React Vite" cmd /k "cd frontend && npm run dev"
echo.

echo =====================================================================
echo  ¡Proceso completado! Se han abierto dos terminales separadas:
echo   - Backend: http://localhost:8080 (Swagger: /swagger-ui.html)
echo   - Frontend: http://localhost:5173
echo =====================================================================
pause
