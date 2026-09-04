param(
    [string]$ProjectName = "knowflow-recovery",
    [string]$EnvFile = "target/compose-acceptance.env"
)

$ErrorActionPreference = "Stop"
$composeArgs = @("-p",$ProjectName,"--env-file",$EnvFile,"-f","compose.yaml","-f","scripts/compose.acceptance.yaml","--profile","app")
$token = $null
$events = [System.Collections.Generic.List[object]]::new()

function Invoke-Compose([string[]]$Arguments) {
    & docker compose @composeArgs @Arguments
    if ($LASTEXITCODE -ne 0) { throw "Compose 操作失败：$($Arguments -join ' ')" }
}

function Invoke-Api([string]$Method,[string]$Path,[object]$Body=$null,[int[]]$Expected=@(200)) {
    $headers = @{}
    if ($token) { $headers.Authorization = "Bearer $token" }
    $params = @{ Uri = "http://127.0.0.1:18088/api$Path"; Method = $Method; Headers = $headers; SkipHttpErrorCheck = $true }
    if ($null -ne $Body) { $params.ContentType = "application/json"; $params.Body = ($Body | ConvertTo-Json -Compress) }
    $response = Invoke-WebRequest @params
    $data = if ($response.Content) { $response.Content | ConvertFrom-Json } else { $null }
    if ($Expected -notcontains [int]$response.StatusCode) { throw "$Method $Path 返回 $($response.StatusCode)" }
    return @{ status = [int]$response.StatusCode; data = $data }
}

function Get-Task([long]$Id) { return (Invoke-Api GET "/document-tasks/$Id").data }
function Wait-Succeeded([long]$Id) {
    for ($i=0; $i -lt 180; $i++) {
        $task = Get-Task $Id
        if ($task.status -eq "FAILED") { throw "任务 $Id 失败：$($task.errorCode)" }
        if ($task.status -eq "SUCCEEDED") { return $task }
        Start-Sleep -Seconds 1
    }
    throw "任务 $Id 等待超时"
}
function Upload([string]$Name) {
    $headers = @{ Authorization = "Bearer $token"; "Idempotency-Key" = [Guid]::NewGuid().ToString() }
    $response = Invoke-WebRequest -Uri "http://127.0.0.1:18088/api/knowledge-bases/$baseId/documents" -Method Post -Headers $headers -Form @{ file = Get-Item -LiteralPath "docs/demo.md" } -SkipHttpErrorCheck
    if ($response.StatusCode -ne 202) { throw "上传 $Name 失败：$($response.StatusCode)" }
    return ($response.Content | ConvertFrom-Json)
}

try {
    Invoke-Compose @("up","-d","--build","--wait","--wait-timeout","180")
    $credentials = @{ username = "recovery_$([Guid]::NewGuid().ToString('N'))"; password = "Recovery_$([Guid]::NewGuid().ToString('N'))" }
    Invoke-Api -Method POST -Path "/auth/register" -Body $credentials -Expected @(201) | Out-Null
    $token = (Invoke-Api POST "/auth/login" $credentials).data.accessToken
    $baseId = (Invoke-Api -Method POST -Path "/knowledge-bases" -Body @{ name = "恢复演练" } -Expected @(201)).data.id

    Invoke-Compose @("pause","worker")
    $workerUpload = Upload "worker"
    Start-Sleep -Seconds 3
    $workerPaused = Get-Task $workerUpload.taskId
    if ($workerPaused.status -eq "SUCCEEDED") { throw "Worker 暂停后任务不应已完成" }
    Invoke-Compose @("unpause","worker")
    Wait-Succeeded $workerUpload.taskId | Out-Null
    $events.Add(@{ name="worker_pause_resume"; result="PASS"; taskId=$workerUpload.taskId })

    Invoke-Compose @("pause","kafka")
    $kafkaUpload = Upload "kafka"
    Start-Sleep -Seconds 5
    $kafkaPending = Get-Task $kafkaUpload.taskId
    if ($kafkaPending.status -eq "SUCCEEDED") { throw "Kafka 暂停后任务不应已完成" }
    Invoke-Compose @("unpause","kafka")
    Wait-Succeeded $kafkaUpload.taskId | Out-Null
    $events.Add(@{ name="kafka_pause_resume"; result="PASS"; taskId=$kafkaUpload.taskId })

    Invoke-Compose @("pause","redis")
    $redisResponse = Invoke-Api GET "/document-tasks/$($workerUpload.taskId)" $null @(200)
    Invoke-Compose @("unpause","redis")
    $events.Add(@{ name="redis_pause_database_fallback"; result=if($redisResponse.status -eq 200){"PASS"}else{"FAIL"}; status=$redisResponse.status })

    Invoke-Compose @("pause","qdrant")
    $qdrantUnavailable = Invoke-Api -Method POST -Path "/knowledge-bases/$baseId/search" -Body @{ query="知识库访问权限"; topK=5; mode="VECTOR"; rerank=$false } -Expected @(503)
    Invoke-Compose @("unpause","qdrant")
    $qdrantRecovered = $null
    for ($i = 0; $i -lt 30; $i++) {
        $candidate = Invoke-Api -Method POST -Path "/knowledge-bases/$baseId/search" -Body @{ query="知识库访问权限"; topK=5; mode="VECTOR"; rerank=$false } -Expected @(200,503)
        if ($candidate.status -eq 200) { $qdrantRecovered = $candidate; break }
        Start-Sleep -Seconds 1
    }
    if ($null -eq $qdrantRecovered) { throw "Qdrant 恢复后仍不可用" }
    if (@($qdrantRecovered.data).Count -eq 0) { throw "Qdrant 恢复后未返回检索结果" }
    $events.Add(@{ name="qdrant_pause_resume"; result="PASS"; unavailableStatus=$qdrantUnavailable.status; recoveredHits=@($qdrantRecovered.data).Count })

    $report = @{ generatedAt=[DateTime]::UtcNow.ToString("o"); project=$ProjectName; model="LOCAL_PROTOCOL_FIXTURE"; result="PASS"; events=$events }
    $report | ConvertTo-Json -Depth 10 | Set-Content -Encoding utf8 "target/recovery-drill-result.json"
    $report | ConvertTo-Json -Depth 10
}
finally {
    foreach ($service in @("worker","kafka","redis","qdrant")) { & docker compose @composeArgs unpause $service *> $null }
    & docker compose @composeArgs down --remove-orphans *> $null
}
