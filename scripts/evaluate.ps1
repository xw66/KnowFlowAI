param([string]$BaseUrl='http://localhost:8080',[int]$WaitSeconds=180,[switch]$EvaluateRerank)
$ErrorActionPreference='Stop'; $BaseUrl=$BaseUrl.TrimEnd('/')
$credentials=@{username='eval_'+[guid]::NewGuid().ToString('N');password='Eval_'+[Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(24))}|ConvertTo-Json
$null=Invoke-RestMethod "$BaseUrl/api/auth/register" -Method Post -ContentType 'application/json' -Body $credentials
$login=Invoke-RestMethod "$BaseUrl/api/auth/login" -Method Post -ContentType 'application/json' -Body $credentials
$headers=@{Authorization="Bearer $($login.accessToken)"}; $base=Invoke-RestMethod "$BaseUrl/api/knowledge-bases" -Method Post -Headers $headers -ContentType 'application/json' -Body '{"name":"KnowFlow 评测集"}'
$headers['Idempotency-Key']=[guid]::NewGuid().ToString(); $file=Get-Item (Join-Path $PSScriptRoot '../docs/demo.md')
$upload=Invoke-RestMethod "$BaseUrl/api/knowledge-bases/$($base.id)/documents" -Method Post -Headers $headers -Form @{file=$file}
$deadline=[DateTimeOffset]::UtcNow.AddSeconds($WaitSeconds); do {$task=Invoke-RestMethod "$BaseUrl/api/document-tasks/$($upload.taskId)" -Headers $headers;if($task.status -eq 'FAILED'){throw $task.errorCode};if($task.status -eq 'SUCCEEDED'){break};Start-Sleep 1} while([DateTimeOffset]::UtcNow -lt $deadline)
if($task.status -ne 'SUCCEEDED'){throw '评测索引任务超时'}
$query='如何申请知识库访问权限？'; $expected=$upload.documentId; $rows=@()
foreach($mode in @('VECTOR','BM25','HYBRID')) {
  $body=@{query=$query;topK=5;mode=$mode;rerank=$false}|ConvertTo-Json
  $response=Invoke-WebRequest "$BaseUrl/api/knowledge-bases/$($base.id)/search" -Method Post -Headers $headers -ContentType 'application/json' -Body $body
  $hits=$response.Content|ConvertFrom-Json; $rank=0; foreach($hit in @($hits)){$rank++;if($hit.documentId -eq $expected){break}}; $found=($rank -le @($hits).Count -and $rank -gt 0 -and $hits[$rank-1].documentId -eq $expected)
  $rows+= [pscustomobject]@{mode=$mode;recallAt5=[int]$found;mrr=if($found){1.0/$rank}else{0};ndcgAt5=if($found){1.0/[math]::Log($rank+1,2)}else{0};scoreType=$response.Headers['X-Search-Score-Type'];rerankStatus=$response.Headers['X-Rerank-Status'];hits=@($hits)}
}
if($EvaluateRerank) {
  $body=@{query=$query;topK=5;mode='HYBRID';rerank=$true}|ConvertTo-Json
  $response=Invoke-WebRequest "$BaseUrl/api/knowledge-bases/$($base.id)/search" -Method Post -Headers $headers -ContentType 'application/json' -Body $body
  $hits=$response.Content|ConvertFrom-Json; $rank=0; foreach($hit in @($hits)){$rank++;if($hit.documentId -eq $expected){break}}; $found=($rank -le @($hits).Count -and $rank -gt 0 -and $hits[$rank-1].documentId -eq $expected)
  $status=$response.Headers['X-Rerank-Status']; $rows+= [pscustomobject]@{mode='HYBRID+RERANK';effectiveMode=if($status -eq 'APPLIED'){'HYBRID+RERANK'}else{'HYBRID/RRF fallback'};recallAt5=[int]$found;mrr=if($found){1.0/$rank}else{0};ndcgAt5=if($found){1.0/[math]::Log($rank+1,2)}else{0};scoreType=$response.Headers['X-Search-Score-Type'];rerankStatus=$status;hits=@($hits)}
}
$report=[pscustomobject]@{generatedAt=(Get-Date).ToUniversalTime().ToString('o');dataset='docs/demo.md / 1 query';knowledgeBaseId=$base.id;results=$rows;rerank=if($EvaluateRerank){'REQUESTED'}else{'NOT_EVALUATED'}}
$path=Join-Path $PSScriptRoot '../target/retrieval-evaluation.json';$report|ConvertTo-Json -Depth 8|Set-Content $path; $report.results|Select-Object mode,recallAt5,mrr,ndcgAt5,scoreType,rerankStatus;Write-Output "原始结果已保存：$path"
