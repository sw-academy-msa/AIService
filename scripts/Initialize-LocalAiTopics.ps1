<# 공유 로컬 Kafka에 AI 관련 토픽을 추가하며 기존 토픽이나 데이터를 삭제하지 않습니다. 운영 설정은 별도입니다. #>
param([string]$KafkaContainer = 'stockdrop-query-kafka-kafka-1')
$ErrorActionPreference = 'Stop'
foreach ($topic in @('stockdrop.product.analysis-requested.v1','stockdrop.ai.product-analyzed.v1','stockdrop.product.published.v1','stockdrop.ai.recommendation-candidates.v1')) {
    & docker exec $KafkaContainer /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --create --if-not-exists --topic $topic --partitions 3 --replication-factor 1
    if ($LASTEXITCODE -ne 0) { throw '공유 로컬 Kafka의 AI 토픽 준비에 실패했습니다.' }
}
