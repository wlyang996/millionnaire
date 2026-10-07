# 把 Cocos「Web 手机端」构建产物复制到 web/dist，然后自动提交并推送（在仓库根目录或 web 目录下运行均可）
# 用法：powershell -ExecutionPolicy Bypass -File web\update-dist.ps1 [-Build web-mobile-001] [-NoPush]
#   -Build   构建目录名（client\build 下），默认 web-mobile-001
#   -NoPush  只复制，不提交、不推送
# 只提交 web/dist 目录里的变化，不会带上其他未提交的改动。
param([string]$Build = "web-mobile-001", [switch]$NoPush)
$root = Split-Path -Parent $PSScriptRoot
$src = Join-Path $root "client\build\$Build"
$dst = Join-Path $PSScriptRoot "dist"
if (-not (Test-Path (Join-Path $src "index.html"))) {
    Write-Error "找不到构建产物：$src\index.html。请先在 Cocos 里构建「Web 手机端」，或用 -Build 指定构建目录名。"
    exit 1
}
if (Test-Path $dst) { Remove-Item -Recurse -Force $dst }
Copy-Item -Recurse $src $dst
Write-Host "已复制到 $dst"
if ($NoPush) {
    Write-Host "已跳过提交与推送（-NoPush）。"
    exit 0
}

if (-not (Get-Command git -ErrorAction SilentlyContinue)) {
    Write-Error "找不到 git 命令，请先安装 Git 并加入 PATH，或手动提交：git add web/dist; git commit; git push"
    exit 1
}
Set-Location $root
$branch = (git rev-parse --abbrev-ref HEAD).Trim()
if ($LASTEXITCODE -ne 0) { Write-Error "当前目录不是 git 仓库：$root"; exit 1 }

# 1. 只暂存 web/dist（含删除的旧文件）
git add -A -- web/dist
git diff --cached --quiet -- web/dist
if ($LASTEXITCODE -eq 0) {
    Write-Host "web/dist 没有变化，不需要提交。"
    exit 0
}

# 2. 只提交 web/dist，不带上其他已暂存的改动
$stamp = Get-Date -Format "yyyy-MM-dd HH:mm"
git commit -m "Update web build ($stamp)" -- web/dist
if ($LASTEXITCODE -ne 0) { Write-Error "提交失败，请查看上面的 git 输出。"; exit 1 }

# 3. 先拉取远端的新提交（合并，不改写历史；本地其他未提交的改动先自动暂存、合并后还原），再推送
Write-Host "正在拉取 origin/$branch 的最新提交…"
git pull --no-rebase --no-edit --autostash origin $branch
if ($LASTEXITCODE -ne 0) {
    Write-Error "拉取 / 合并失败（可能有冲突）。本次构建已在本地提交，请解决冲突后再执行：git push origin $branch"
    exit 1
}
Write-Host "正在推送到 origin/$branch …"
git push origin $branch
if ($LASTEXITCODE -ne 0) { Write-Error "推送失败，请检查网络或权限后重试：git push origin $branch"; exit 1 }
Write-Host ""
Write-Host "完成：已提交并推送到 $branch。"
if ($branch -eq "prod") { Write-Host "注意：推送 prod 可能触发云托管自动发布（发布会解散进行中的对局）。" }
