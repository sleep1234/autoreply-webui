@rem Gradle startup script for Windows
@if "%DEBUG%"=="" @echo off
set DIRNAME=%~dp0
if "%DIRNAME%"=="" set DIRNAME=.
set APP_HOME=%DIRNAME%
for %%i in ("%APP_HOME%") do set APP_HOME=%%~fi
set CLASSPATH=%APP_HOME%\gradle\wrapper\gradle-wrapper.jar
set GRADLE_OPTS=-Dhttp.proxyHost=192.168.2.2 -Dhttp.proxyPort=1082 -Dhttps.proxyHost=192.168.2.2 -Dhttps.proxyPort=1082
"%JAVA_HOME%\bin\java.exe" %DEFAULT_JVM_OPTS% %GRADLE_OPTS% -classpath "%CLASSPATH%" org.gradle.wrapper.GradleWrapperMain %*
