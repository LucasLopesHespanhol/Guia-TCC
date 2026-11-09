@echo off
title Exportador do notebook (porta 9182)
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0exporter-notebook.ps1"
