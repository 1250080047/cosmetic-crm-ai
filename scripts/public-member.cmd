@echo off
chcp 65001 >nul
rem Mo trang the thanh vien (/member) ra Internet qua Cloudflare Quick Tunnel (mien phi, khong can tai khoan).
rem Khach dung 4G hay Wi-Fi nao cung mo duoc. Chi cong 8081 (chi co trang the khach) duoc dua ra ngoai;
rem POS va trang quan tri van chi mo trong may / Wi-Fi cua cua hang.
rem Giu cua so nay mo trong luc ban hang; dong cua so la tat duong ham.

set "CLOUDFLARED="
for /f "delims=" %%P in ('where cloudflared 2^>nul') do if not defined CLOUDFLARED set "CLOUDFLARED=%%P"
if not defined CLOUDFLARED if exist "%ProgramFiles(x86)%\cloudflared\cloudflared.exe" set "CLOUDFLARED=%ProgramFiles(x86)%\cloudflared\cloudflared.exe"
if not defined CLOUDFLARED if exist "%ProgramFiles%\cloudflared\cloudflared.exe" set "CLOUDFLARED=%ProgramFiles%\cloudflared\cloudflared.exe"
if not defined CLOUDFLARED (
    echo Chưa cài cloudflared. Mở PowerShell và chạy:
    echo     winget install --id Cloudflare.cloudflared -e
    pause
    exit /b 1
)

powershell -NoProfile -Command "if (-not (Test-NetConnection 127.0.0.1 -Port 8081 -InformationLevel Quiet -WarningAction SilentlyContinue)) { exit 1 }"
if errorlevel 1 (
    echo Ứng dụng Spring chưa chạy ^(cổng 8081 chưa mở^). Hãy chạy "mvn spring-boot:run" trong thư mục frontend-pos trước.
    pause
    exit /b 1
)

echo Đang mở đường hầm... Địa chỉ https://....trycloudflare.com sẽ hiện bên dưới sau vài giây.
echo Ở POS bấm "📱 Thẻ thành viên" để lấy mã QR của địa chỉ này. Đóng cửa sổ này là tắt đường hầm.
echo.
"%CLOUDFLARED%" tunnel --no-autoupdate --metrics 127.0.0.1:20241 --url http://127.0.0.1:8081
pause
