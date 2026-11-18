# STOCKDROP AI Service

Java 21, Spring Boot 4.1.1, Spring Cloud 2025.1.4의 MVC 앱입니다.
상품 이미지 기반 카테고리 추천과 등록정보 점검 질문을 비동기로 생성합니다.
카테고리와 점검 기준 원본은 Product Command의 Flyway가 관리합니다.
AI는 전달받은 기준표로 분석하며 상품 원본 DB에 접근하지 않습니다.

## 로컬 실행

1. ./scripts/Initialize-LocalAiDatabase.ps1을 실행해 기존 일반 PostgreSQL(5433)에 AI 전용 ai_service DB와 ai 계정·스키마를 준비합니다. 기존 DB와 역할 비밀번호는 변경하지 않습니다.
2. 초기화 스크립트가 새 .env와 임의 비밀번호를 만들며 기존 .env는 유지합니다. 실제 .env는 Git 제외입니다.
3. Java 21로 ./gradlew.bat bootRun을 실행합니다. 기본 포트는 8086입니다.
4. Kafka 소비, 작업자, Outbox 발행, 유료 모델 호출은 기본 비활성입니다.

실제 분석에는 AI_CONSUMER_ENABLED, AI_WORKER_ENABLED, AI_OUTBOX_PUBLISHER_ENABLED,
AI_VISION_ENABLED를 true로 설정하고 OPENAI_API_KEY와 Product 이미지 버킷 읽기 권한을 주입합니다.
Product에서도 PRODUCT_OUTBOX_PUBLISHER_ENABLED=true와 PRODUCT_AI_CONSUMER_ENABLED=true가 필요합니다.
키는 프런트나 Product Command에 넣지 않고 AI 서비스에만 주입합니다.
Eureka 등록 이름은 ai-service, 외부 결과 조회는 일반 Gateway(8000)를 사용합니다.
이미지와 Kafka·DB는 기존 공유 로컬 인프라를 사용하며 이 앱의 Compose는 별도 인프라를 만들지 않습니다.
Compose에서는 호스트 서비스가 Docker 브리지에서 접근 가능해야 하며 Kafka advertised listener도 맞아야 합니다.
운영은 일반 풀 A/B, AI 전용 계정으로 RDS에 연결하며 운영 URL과 HTTPS JWKS 주소를 주입합니다.
개발 ddl-auto는 update, 운영은 validate이며 운영 스키마 반영은 배포 전에 별도로 준비합니다.

## API와 검증

GET /api/v1/ai/product-analyses/{requestId}, SELLER 본인 결과만 ApiResponse로 반환합니다.
분석 요청은 Product Command가 접수하며, 확인된 답변의 저장은 기존 Product FAQ API가 담당합니다.
상세 계약과 호출 순서는 [상품 분석 계약](contracts/product-analysis-v1.md)을 참고합니다.
./gradlew.bat test bootJar는 격리 PostgreSQL과 로컬 HTTP 대역을 사용하며 OpenAI 유료 호출을 하지 않습니다.
실제 모델 정확도, 실제 키 연동, 운영 S3/Kafka/Eureka 연결은 별도 검증 대상입니다.
GA4 조회 기반 관심 카테고리 분석과 최초 공개 상품의 알림 후보 생성을 추가했습니다.
최근 30일 최다 조회 카테고리 1개를 하루 한 번 계산하며 같은 상품은 사용자별 하루 한 번만 집계합니다.
GA 수집은 프런트 태그와 Google BigQuery 내보내기 설정이 별도로 필요하며 기본 비활성입니다.
실제 알림 발송은 아직 없는 NotificationService가 후보를 소비하고 최신 수신 설정을 확인한 뒤 담당합니다.
[GA 수집·참여 API·Google 설정·알림 후보 계약](contracts/ga-category-recommendations-v1.md)을 확인합니다.
구매자 실시간 챗봇은 구현하지 않았습니다.
Git init, add, commit, push는 실행하지 않습니다.

카테고리 목록은 Product Command에서 기존 분석 요청으로 받습니다. 255개 분류를 수용하며 벡터 DB, 임베딩과 GA 추천 로직 변경은 포함하지 않습니다.
