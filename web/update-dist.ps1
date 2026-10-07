# 把 Cocos「Web 手机端」构建产物复制到 web/dist，然后自动提交并推送（在仓库根目录或 web 目录下运行均可）
# 用法：powershell -ExecutionPolicy Bypass -File web\update-dist.ps1 [-Build web-mobile-001] [-NoPush] [-Force]
#   -Build   构建目录名（client\build 下）；不指定时自动选 client\build 里最近一次构建的目录
#   -NoPush  只复制，不提交、不推送
#   -Force   构建产物比最新代码旧时仍然继续（默认会停下，避免把旧包当新包推上去）
# 只提交 web/dist 目录里的变化，不会带上其他未提交的改动。
param([string]$Build = "", [switch]$NoPush, [switch]$Force)
$root = Split-Path -Parent $PSScriptRoot
$buildRoot = Join-Path $root "client\build"
$dst = Join-Path $PSScriptRoot "dist"

# 1. 找构建目录：指定了就用指定的，否则选 index.html 最新的那个
$candidates = @()
if (Test-Path $buildRoot) {
    $candidates = @(Get-ChildItem $buildRoot -Directory | Where-Object { Test-Path (Join-Path $_.FullName "index.html") } |
        ForEach-Object { [pscustomobject]@{ Name = $_.Name; Path = $_.FullName; Time = (Get-Item (Join-Path $_.FullName "index.html")).LastWriteTime } } |
        Sort-Object Time -Descending)
}
if ($candidates.Count -eq 0) {
    Write-Error "client\build 下没有找到任何构建产物（含 index.html 的目录）。请先在 Cocos 里构建「Web 手机端」。"
    exit 1
}
Write-Host "client\build 下的构建产物（新的在前）："
$candidates | ForEach-Object { Write-Host ("  {0,-20} 构建于 {1:yyyy-MM-dd HH:mm:ss}" -f $_.Name, $_.Time) }
if ($Build) {
    $chosen = $candidates | Where-Object { $_.Name -eq $Build } | Select-Object -First 1
    if (-not $chosen) { Write-Error "找不到构建产物：$buildRoot\$Build\index.html"; exit 1 }
} else {
    $chosen = $candidates[0]
}
Write-Host ("使用：{0}（构建于 {1:yyyy-MM-dd HH:mm:ss}）" -f $chosen.Name, $chosen.Time)

# 2. 产物不能比最新的客户端代码旧（git 可用时检查；只看代码与素材，不看 Cocos 生成的 .meta）
$hasGit = [bool](Get-Command git -ErrorAction SilentlyContinue)
if ($hasGit) {
    Push-Location $root
    $codeTs = git log -1 --format=%ct -- client/assets ':(exclude,glob)**/*.meta' 2>$null
    Pop-Location
    if ($codeTs) {
        $codeTime = [DateTimeOffset]::FromUnixTimeSeconds([int64]$codeTs).LocalDateTime
        if ($chosen.Time -lt $codeTime -and -not $Force) {
            Write-Error ("构建产物比最新代码旧：产物构建于 {0:yyyy-MM-dd HH:mm}，客户端代码最后更新于 {1:yyyy-MM-dd HH:mm}。请先 git pull，再在 Cocos 里重新构建「Web 手机端」；确定要用这份旧产物请加 -Force。" -f $chosen.Time, $codeTime)
            exit 1
        }
    }
}

# 3. 复制
if (Test-Path $dst) { Remove-Item -Recurse -Force $dst }
Copy-Item -Recurse $chosen.Path $dst
Write-Host "已复制到 $dst"
if ($NoPush) {
    Write-Host "已跳过提交与推送（-NoPush）。"
    exit 0
}

if (-not $hasGit) {
    Write-Error "找不到 git 命令，请先安装 Git 并加入 PATH，或手动提交：git add web/dist; git commit; git push"
    exit 1
}
Set-Location $root
$branch = (git rev-parse --abbrev-ref HEAD).Trim()
if ($LASTEXITCODE -ne 0) { Write-Error "当前目录不是 git 仓库：$root"; exit 1 }

# 4. 只暂存 web/dist（含删除的旧文件）
git add -A -- web/dist
git diff --cached --quiet -- web/dist
if ($LASTEXITCODE -eq 0) {
    Write-Host "web/dist 没有变化，不需要提交（复制过去的产物和仓库里的一样）。"
    exit 0
}

# 5. 只提交 web/dist，不带上其他已暂存的改动
$stamp = Get-Date -Format "yyyy-MM-dd HH:mm"
git commit -m "Update web build ($stamp, $($chosen.Name))" -- web/dist
if ($LASTEXITCODE -ne 0) { Write-Error "提交失败，请查看上面的 git 输出。"; exit 1 }

# 6. 先拉取远端的新提交（合并，不改写历史；本地其他未提交的改动先自动暂存、合并后还原），再推送
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
