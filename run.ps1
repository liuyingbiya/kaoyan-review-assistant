<#
  考研复习助手 · 一键启动

  用法：
    .\run.ps1                       # 用现有数据库启动（无 key 则走模拟模式）
    .\run.ps1 -Key "sk-xxx"         # 指定 DeepSeek Key 启动（真实模型）
    .\run.ps1 -Rebuild              # 先重新打包再启动

  为什么固定 JAVA_HOME？
    这台机器装了多个 JDK，默认可能是 17/21，而本项目是
    JDK 8 + Spring Boot 2.7。用高版本 JDK 启动会遇到字节码与反射限制问题，
    所以这里显式指定 1.8。
#>
param(
    [string]$Key = "",
    [switch]$Rebuild
)

$ErrorActionPreference = "Stop"

$JavaHome = "E:\envriorment\java"
$Maven    = "E:\envriorment\maven\apache-maven-3.8.4\bin\mvn.cmd"
$Root     = Split-Path -Parent $MyInvocation.MyCommand.Definition
$Jar      = Join-Path $Root "backend\target\ai-knowledge-base-1.0.0.jar"

# ---------- 1. 打包 ----------
if ($Rebuild -or -not (Test-Path $Jar)) {
    Write-Host "==> 打包中（前端会一起打进 jar）..." -ForegroundColor Cyan
    Push-Location (Join-Path $Root "backend")
    & $Maven -q -DskipTests package
    Pop-Location
}

# ---------- 2. 模型 Key ----------
if ($Key -ne "") {
    $env:DEEPSEEK_API_KEY = $Key
    Write-Host "==> 已设置 DEEPSEEK_API_KEY（真实模型模式）" -ForegroundColor Green
} elseif ($env:DEEPSEEK_API_KEY) {
    Write-Host "==> 检测到环境变量 DEEPSEEK_API_KEY（真实模型模式）" -ForegroundColor Green
} else {
    Write-Host "==> 未配置 Key，使用本地模拟模式（检索片段拼接，链路照样能跑）" -ForegroundColor Yellow
}

# ---------- 3. 启动 ----------
New-Item -ItemType Directory -Force -Path (Join-Path $Root ".runtime") | Out-Null

Write-Host ""
Write-Host "  http://localhost:8081" -ForegroundColor Cyan
Write-Host ""

# 必须以 backend 为工作目录：application.properties 里 rag.seed-dir=../seed 是相对路径
Push-Location (Join-Path $Root "backend")
& (Join-Path $JavaHome "bin\java.exe") `
    "-Dfile.encoding=UTF-8" `
    "-Djava.io.tmpdir=$Root\.runtime" `
    -jar $Jar
Pop-Location
