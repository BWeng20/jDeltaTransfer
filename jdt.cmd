@echo off
setlocal
rem Convenience wrapper around the fat jar.
rem   jdt server --archives data\archives --store data\store
rem   jdt client list
rem   jdt gen --out data\archives

set "JAR=%~dp0build\libs\jDeltaTransfer-1.0.0-all.jar"
if not exist "%JAR%" (
    echo Fat jar not found. Build it first:  gradlew fatJar
    exit /b 1
)
if "%JDT_JAVA%"=="" (
    if not "%JAVA_HOME%"=="" (
        set "JDT_JAVA=%JAVA_HOME%\bin\java.exe"
    ) else (
        set "JDT_JAVA=java"
    )
)
if "%JDT_OPTS%"=="" set "JDT_OPTS=-Xmx4g"

"%JDT_JAVA%" %JDT_OPTS% -jar "%JAR%" %*
