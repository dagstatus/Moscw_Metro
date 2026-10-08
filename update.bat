@echo off
chcp 65001 >nul
cd /d "%~dp0"

rem Ищем Java: JAVA_HOME, затем Java из Android Studio, затем java из PATH
set "JAVA=java"
if exist "%ProgramFiles%\Android\Android Studio\jbr\bin\java.exe" set "JAVA=%ProgramFiles%\Android\Android Studio\jbr\bin\java.exe"
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA=%JAVA_HOME%\bin\java.exe"

if not "%~1"=="" (
  "%JAVA%" tools\Build.java %*
  goto :eof
)

:menu
echo.
echo   Метро Москвы — обновление приложения
echo   1  Проверить данные (data\*.csv)
echo   2  Открыть схему в браузере
echo   3  Собрать тестовый APK (версия не меняется)
echo   4  Выпустить обновление (новая версия + подписанный APK)
echo   0  Выход
echo.
set "c="
set /p c=Выберите действие: 
if "%c%"=="1" "%JAVA%" tools\Build.java check
if "%c%"=="2" "%JAVA%" tools\Build.java preview
if "%c%"=="3" "%JAVA%" tools\Build.java apk
if "%c%"=="4" "%JAVA%" tools\Build.java release
if "%c%"=="0" goto :eof
goto menu
