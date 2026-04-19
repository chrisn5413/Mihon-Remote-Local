@rem Gradle startup script for Windows.
@rem If gradle-wrapper.jar is missing, run:
@rem   gradle wrapper --gradle-version 8.6
@rem Or open the project in Android Studio.

@if "%DEBUG%"=="" @echo off
@rem Set local scope for the variables with windows NT shell
if "%OS%"=="Windows_NT" setlocal

set DIRNAME=%~dp0
if "%DIRNAME%"=="" set DIRNAME=.

set APP_BASE_NAME=%~n0
set APP_HOME=%DIRNAME%

set CLASSPATH=%APP_HOME%\gradle\wrapper\gradle-wrapper.jar

if not exist "%CLASSPATH%" (
  echo ERROR: gradle-wrapper.jar not found. Run "gradle wrapper --gradle-version 8.6" or open in Android Studio.
  exit /b 1
)

set DEFAULT_JVM_OPTS="-Xmx64m" "-Xms64m"

java %DEFAULT_JVM_OPTS% -classpath "%CLASSPATH%" org.gradle.wrapper.GradleWrapperMain %*

:end
if "%OS%"=="Windows_NT" endlocal
