param(
    [string]$BaseUrl = 'http://127.0.0.1:18080',
    [string]$DatasetPath = 'evaluation/v1/dataset.json',
    [int]$WaitSeconds = 180,
    [string]$OutputPath = 'target/retrieval-evaluation-v1.json',
    [switch]$EvaluateRerank
)

$ErrorActionPreference = 'Stop'
$BaseUrl = $BaseUrl.TrimEnd('/')
$dataset = Get-Content -LiteralPath $DatasetPath -Raw | ConvertFrom-Json
$script:apiHeaders = @{}
$credentials = @{ username = 'eval_' + [guid]::NewGuid().ToString('N'); password = 'Eval_' + [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(24)) }
$temp = Join-Path ([IO.Path]::GetTempPath()) ('knowflow-eval-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $temp | Out-Null

function Api([string]$Method, [string]$Path, [object]$Body = $null) {
    $params = @{ Uri = "$BaseUrl/api$Path"; Method = $Method }
    if ($script:apiHeaders.Count) { $params.Headers = $script:apiHeaders }
    if ($null -ne $Body) { $params.ContentType = 'application/json'; $params.Body = $Body | ConvertTo-Json -Compress }
    Invoke-RestMethod @params
}

function Metric([object[]]$Hits, [string[]]$Relevant) {
    $ids = @($Hits | ForEach-Object { [string]$_.documentId })
    $ranks = @($Relevant | ForEach-Object { $index = [Array]::IndexOf($ids, $_); if ($index -ge 0 -and $index -lt 5) { $index + 1 } }) | Sort-Object
    $recall = [int]($ranks.Count -gt 0)
    $mrr = if ($ranks.Count) { 1.0 / $ranks[0] } else { 0.0 }
    $dcg = 0.0; foreach ($rank in $ranks) { $dcg += 1.0 / [Math]::Log($rank + 1, 2) }
    $ideal = 0.0; for ($rank = 1; $rank -le [Math]::Min(5, $Relevant.Count); $rank++) { $ideal += 1.0 / [Math]::Log($rank + 1, 2) }
    [pscustomobject]@{ recallAt5 = $recall; mrrAt5 = $mrr; ndcgAt5 = if ($ideal) { $dcg / $ideal } else { 0.0 } }
}

function Search([long]$KnowledgeBaseId, [hashtable]$Body) {
    for ($attempt = 0; $attempt -lt 180; $attempt++) {
        $response = Invoke-WebRequest "$BaseUrl/api/knowledge-bases/$KnowledgeBaseId/search" -Method Post -Headers $script:apiHeaders -ContentType 'application/json' -Body ($Body | ConvertTo-Json -Compress) -SkipHttpErrorCheck
        if ($response.StatusCode -eq 200) { return $response }
        if ($response.StatusCode -ne 429) { throw "搜索失败：HTTP $($response.StatusCode)" }
        $wait = 1
        if ($response.Headers['Retry-After']) { [int]::TryParse([string]$response.Headers['Retry-After'], [ref]$wait) | Out-Null }
        Start-Sleep -Seconds ([Math]::Max(1, $wait))
    }
    throw '搜索限流等待超时'
}

try {
    Api POST '/auth/register' $credentials | Out-Null
    $login = Api POST '/auth/login' $credentials
    $headers = @{ Authorization = "Bearer $($login.accessToken)" }
    $script:apiHeaders = $headers
    $base = Api POST '/knowledge-bases' @{ name = 'KnowFlow 评测集 v1' }
    $documentIds = @{}
    $taskIds = @()
    foreach ($document in @($dataset.documents)) {
        $filePath = Join-Path $temp $document.name
        Set-Content -LiteralPath $filePath -Value $document.content -Encoding utf8
        $headers['Idempotency-Key'] = [guid]::NewGuid().ToString()
        $upload = Invoke-WebRequest "$BaseUrl/api/knowledge-bases/$($base.id)/documents" -Method Post -Headers $headers -Form @{ file = Get-Item -LiteralPath $filePath }
        $uploaded = $upload.Content | ConvertFrom-Json
        $documentIds[$document.id] = $uploaded.documentId
        $taskIds += $uploaded.taskId
    }
    $deadline = [DateTimeOffset]::UtcNow.AddSeconds($WaitSeconds)
    do {
        $tasks = @($taskIds | ForEach-Object { Api GET "/document-tasks/$_" })
        if (@($tasks | Where-Object status -eq 'FAILED').Count) { throw '评测文档存在失败任务' }
        $complete = @($tasks | Where-Object status -eq 'SUCCEEDED').Count -eq $taskIds.Count
        if (-not $complete) { Start-Sleep -Seconds 1 }
    } while (-not $complete -and [DateTimeOffset]::UtcNow -lt $deadline)
    if (-not $complete) { throw '评测索引任务超时' }

    $rows = [System.Collections.Generic.List[object]]::new()
    $modes = @('VECTOR', 'BM25', 'HYBRID')
    if ($EvaluateRerank) { $modes += 'HYBRID_RERANK' }
    foreach ($query in @($dataset.queries)) {
        foreach ($mode in $modes) {
            $rerank = $mode -eq 'HYBRID_RERANK'
            $requestMode = if ($rerank) { 'HYBRID' } else { $mode }
            $body = @{ query = $query.question; topK = 5; mode = $requestMode; rerank = $rerank } | ConvertTo-Json -Compress
            $response = Search $base.id @{ query = $query.question; topK = 5; mode = $requestMode; rerank = $rerank }
            $hits = @($response.Content | ConvertFrom-Json)
            $relevantIds = @($query.relevant | ForEach-Object { [string]$documentIds[$_] })
            $metric = Metric $hits $relevantIds
            $rows.Add([pscustomobject]@{ queryId = $query.id; mode = $mode; rerankStatus = [string]$response.Headers['X-Rerank-Status']; scoreType = [string]$response.Headers['X-Search-Score-Type']; metrics = $metric; hits = $hits })
        }
    }
    $summary = @($rows | Group-Object mode | ForEach-Object {
        [pscustomobject]@{ mode = $_.Name; queries = $_.Count; recallAt5 = ($_.Group.metrics.recallAt5 | Measure-Object -Average).Average; mrrAt5 = ($_.Group.metrics.mrrAt5 | Measure-Object -Average).Average; ndcgAt5 = ($_.Group.metrics.ndcgAt5 | Measure-Object -Average).Average; rerankApplied = @($_.Group | Where-Object rerankStatus -eq 'APPLIED').Count }
    })
    $report = [pscustomobject]@{ generatedAt = [DateTime]::UtcNow.ToString('o'); dataset = $DatasetPath; knowledgeBaseId = $base.id; documentCount = $dataset.documents.Count; queryCount = $dataset.queries.Count; rerankRequested = [bool]$EvaluateRerank; summary = $summary; results = $rows }
    $output = Join-Path $PSScriptRoot "../$OutputPath"
    $report | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $output -Encoding utf8
    $summary | Format-Table | Out-String | Write-Output
    Write-Output "原始结果已保存：$output"
}
finally {
    if (Test-Path $temp) { Remove-Item -LiteralPath $temp -Recurse -Force }
}
