param(
    [string]$BaseUrl = 'http://localhost:8080',
    [switch]$UploadOnly,
    [switch]$Answer,
    [int]$WaitSeconds = 180
)
$ErrorActionPreference = 'Stop'
if ($PSVersionTable.PSVersion.Major -lt 7) { throw '请使用 PowerShell 7 运行此脚本' }
if ($WaitSeconds -lt 1 -or $WaitSeconds -gt 1800) { throw '等待时间须为 1–1800 秒' }
$BaseUrl = $BaseUrl.TrimEnd('/')
$demoUsername = 'demo_' + [guid]::NewGuid().ToString('N')
$demoPassword = 'Demo_' + [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(24))
$credentials = @{username=$demoUsername; password=$demoPassword} | ConvertTo-Json
$null = Invoke-RestMethod "$BaseUrl/api/auth/register" -Method Post -ContentType 'application/json' -Body $credentials
$login = Invoke-RestMethod "$BaseUrl/api/auth/login" -Method Post -ContentType 'application/json' -Body $credentials
$headers = @{Authorization="Bearer $($login.accessToken)"}
$base = Invoke-RestMethod "$BaseUrl/api/knowledge-bases" -Method Post -Headers $headers -ContentType 'application/json' -Body '{"name":"KnowFlow 演示知识库"}'
$headers['Idempotency-Key'] = [guid]::NewGuid().ToString()
$sample = Get-Item -LiteralPath (Join-Path $PSScriptRoot '../docs/demo.md')
$upload = Invoke-RestMethod "$BaseUrl/api/knowledge-bases/$($base.id)/documents" -Method Post -Headers $headers -Form @{file=$sample}
Write-Output "已创建演示账号 $demoUsername，知识库 $($base.id)，任务 $($upload.taskId)"
if ($UploadOnly) { Write-Output '已验证注册、登录、建库、上传；未执行索引与检索验证。'; return }
$deadline = [DateTimeOffset]::UtcNow.AddSeconds($WaitSeconds)
do {
    $task = Invoke-RestMethod "$BaseUrl/api/document-tasks/$($upload.taskId)" -Headers $headers
    if ($task.status -eq 'FAILED') { throw "任务失败：$($task.errorCode)" }
    if ($task.status -eq 'SUCCEEDED') { break }
    if ([DateTimeOffset]::UtcNow -ge $deadline) { throw "任务等待超时：$($task.status)/$($task.stage)，请检查 Worker、Kafka 和 Embedding 配置" }
    Start-Sleep -Seconds 1
} while ($true)
$hits = Invoke-RestMethod "$BaseUrl/api/knowledge-bases/$($base.id)/search" -Method Post -Headers $headers -ContentType 'application/json' -Body '{"query":"如何申请知识库访问权限？","topK":3}'
$hits = @($hits)
if ($hits.Count -eq 0 -or -not ($hits | Where-Object { $_.documentId -eq $upload.documentId -and -not [string]::IsNullOrWhiteSpace($_.content) })) {
    throw '检索没有返回当前演示文档的正文，不能判定演示成功'
}
$hits | Select-Object documentName, paragraphNumber, pageNumber, score, content
if ($Answer) {
    $answerResult = Invoke-RestMethod "$BaseUrl/api/knowledge-bases/$($base.id)/answers" -Method Post -Headers $headers -ContentType 'application/json' -Body '{"question":"如何申请知识库访问权限？","topK":3,"mode":"HYBRID","rerank":false}'
    $answerResult | Select-Object answer, citations, usage, actualModel
}
Write-Output '上传到检索链路验证完成。账号与演示数据保留；密码和令牌不会输出。'
