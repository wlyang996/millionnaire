# 把 Cocos「Web 手机端」构建产物复制到 web/dist（在仓库根目录或 web 目录下运行均可）
# 用法：powershell -ExecutionPolicy Bypass -File web\update-dist.ps1 [-Build web-mobile-001]
param([string]$Build = "web-mobile-001")
$root = Split-Path -Parent $PSScriptRoot
$src = Join-Path $root "client\build\$Build"
$dst = Join-Path $PSScriptRoot "dist"
if (-not (Test-Path (Join-Path $src "index.html"))) {
    Write-Error "找不到构建产物：$src\index.html。请先在 Cocos 里构建「Web 手机端」，或用 -Build 指定构建目录名。"
    exit 1
}
if (Test-Path $dst) { Remove-Item -Recurse -Force $dst }
Copy-Item -Recurse $src $dst
Write-Host "已复制到 $dst。接下来：git add web/dist; git commit -m 'Update web build'; git push"
