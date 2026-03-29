@rem Gradle startup script for Windows
@rem
@if "%DEBUG%"=="" @echo off
@rem Set local scope for the variables
setlocal
set DIRNAME=%~dp0
if "%DIRNAME%"=="" set DIRNAME=.

set APP_BASE_NAME=%~n0
set APP_HOME=%DIRNAME%

set CLASSPATH=%APP_HOME%\gradle\wrapper\gradle-wrapper.jar

set DEFAULT_JVM_OPTS="-Xmx64m" "-Xms64m"

@rem Execute Gradle
"%JAVA_EXE%" %DEFAULT_JVM_OPTS% %JAVA_OPTS% %GRADLE_OPTS% "-Dorg.gradle.appname=%APP_BASE_NAME%" -classpath "%CLASSPATH%" org.gradle.wrapper.GradleWrapperMain %*

:end
@rem End local scope for the variables
if "%ERRORLEVEL%"=="0" goto mainEnd
:fail
exit /b 1
:mainEnd
if "%OS%"=="Windows_NT" endlocal
:omega
