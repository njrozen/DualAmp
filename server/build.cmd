@echo off
rem Builds "DualAmp Server.exe" (next to this folder) with the C# compiler that comes with Windows (.NET Framework 4.8).
rem Nothing to install. Run: build.cmd
cd /d "%~dp0"
"%WINDIR%\Microsoft.NET\Framework64\v4.0.30319\csc.exe" /nologo /target:winexe /optimize+ /platform:anycpu ^
  /win32icon:dualamp.ico /resource:icon.png,icon.png ^
  /r:System.Web.Extensions.dll /r:System.Windows.Forms.dll /r:System.Drawing.dll ^
  /out:"..\DualAmp Server.exe" Window.cs Server.cs Tags.cs Sqlite.cs
