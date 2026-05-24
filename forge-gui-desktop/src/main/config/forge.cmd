@echo off

pushd %~dp0

set "_JAVA_OPTIONS="
set "ULTRON_LEARNING_DIR=%USERPROFILE%\.forge\ultron-learning"
set "ULTRON_TRACE_DIR=%ULTRON_LEARNING_DIR%\trace"

java -version 1>nul 2>nul || (
   echo no java installed
   popd
   exit /b 2
)
for /f tokens^=2^ delims^=.-_^+^" %%j in ('java -fullversion 2^>^&1') do set "jver=%%j"

if %jver% LEQ 16 (
   echo unsupported java
   popd
   exit /b 2
)

if %jver% GEQ 17 (
  java $mandatory.java.args$ -jar $project.build.finalName$
  popd
  exit /b 0
)

popd
