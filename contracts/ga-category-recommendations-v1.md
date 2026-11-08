# GA 카테고리 관심도와 신규 상품 알림 후보 v1

## 책임과 현재 완료 범위

AIService가 GA4 BigQuery Export의 상품 상세 조회를 집계해 사용자별 최다 조회 카테고리 한 개를 저장합니다.
Product Command의 최초 승인 공개 이벤트를 받으면 해당 카테고리 사용자에 대한 후보를 AI Outbox에 저장합니다.
기존 Outbox 발행기가 Kafka에 후보를 전송합니다. GPT를 사용하지 않으며 OpenAI API 키와 무관합니다.
Product 원본·Account 회원 테이블에 직접 접근하거나 서비스 간 SQL JOIN을 하지 않습니다.

현재 NotificationService는 없으며 알림 저장·조회·실제 발송은 이번 구현에 포함되지 않습니다.
후보 발생은 수신 동의나 발송 성공을 의미하지 않습니다. Notification 연결 전에는 알림이 도착하지 않습니다.
프런트 프로젝트의 GA 태그 설치와 화면 코드 변경, 실제 Google 프로젝트 설정과 유료 조회도 실행하지 않았습니다.

## 확정된 집계 기준

- 최근 30개의 완료된 GA 날짜, 오늘은 제외하고 어제까지 집계합니다.
- 상품 상세 `view_item`만 사용하며 사용자·상품·GA 날짜별 마지막 조회 한 건만 셉니다.
- 최다 조회 카테고리 1개, 동률이면 최근 조회, 시각까지 같으면 이름 오름차순으로 결정합니다.
- 하루 1회의 성공 분석을 모든 AI 복제본이 공유합니다. 실패하면 이전 프로필을 유지하고 15분 뒤 재시도합니다.
- GA 속성 타임존과 GA_PROPERTY_TIME_ZONE을 동일하게 설정합니다. 기본은 Asia/Seoul입니다.
- privacy_info.analytics_storage='Yes'이며 서버 발급 가명과 연결되는 기록만 프로필에 반영합니다.
- 데이터가 없으면 관심 카테고리를 임의로 생성하지 않습니다. 선택 관심사와 GA 추론을 혼합하지 않습니다.

GA 일일 내보내기는 특정 시각까지 도착한다는 보장이 없습니다. 어제 일일 테이블이 아직 없으면 재시도합니다.
일일 테이블도 나중에 보완될 수 있어 일일 분석이 최종 확정된 통계라는 뜻은 아닙니다.
다음 분석에서 최근 30일 전체를 다시 집계하므로 늦게 들어온 기록도 반영할 수 있습니다.
공식 안내: https://support.google.com/analytics/answer/9358801

## 프런트 수집 계약

Google Analytics 태그가 있는 웹 화면에서 사용자가 분석 참여에 동의한 뒤 순서대로 호출합니다.
분석 참여 동의와 Account의 개인화 알림·광고 수신 동의는 별개입니다.

1. 일반 Gateway POST `/api/v1/ai/analytics/identity`, BUYER JWT, JSON `{"analyticsConsent":true}`.
2. `data.analyticsId`를 GA의 `user_id` 설정으로 사용합니다. 동일 참여의 재요청은 같은 값을 반환합니다.
3. 실제 상품 상세 화면 조회에 `view_item`을 기록합니다.

```javascript
// analyticsId는 서버 응답의 가명이며 이메일·전화번호·Account ID·JWT를 GA에 전달하지 않습니다.
gtag('consent', 'update', { analytics_storage: 'granted' });
gtag('config', measurementId, { user_id: analyticsId });
gtag('event', 'view_item', {
  items: [{ item_id: product.id, item_category: product.category }]
});
```

`item_category`는 현재 Product Query 상세 응답의 카테고리 이름을 그대로 사용합니다.
예: 의류, 패션잡화, 문구, 일반 생활잡화. Account 관심사 코드나 임의 영문명으로 바꾸지 않습니다.
현재 공개 상품 API가 이름을 제공하므로 이 계약도 이름을 사용하며, 향후 분류 이름 변경 시 GA 매핑도 함께 버전 관리해야 합니다.
`user_id`는 config에 설정하고 custom dimension이나 개별 이벤트 파라미터로 중복 등록하지 않습니다.
상품 목록의 노출이나 새로고침 횟수를 추가 점수로 사용하지 않습니다. 목록 노출은 view_item_list로 구분합니다.
로그아웃 시 user_id를 null로 해제합니다. 비로그인 기록을 다른 계정에 임의로 귀속시키지 않습니다.

분석 참여 철회:

1. 일반 Gateway DELETE `/api/v1/ai/analytics/identity`, BUYER JWT.
2. 프런트에서 user_id를 null로 해제하고 analytics_storage를 denied로 전환하며 수집을 중단합니다.
3. AI의 가명 매핑과 모든 관심도 회차가 삭제됩니다. 다시 참여하면 새 가명을 받습니다.
4. 이미 Google에 수집된 원격 데이터 삭제는 이 API가 수행하지 않습니다. Google 데이터 삭제 절차는 별도입니다.

GET `/api/v1/ai/analytics/identity`는 본인 참여 식별자를 조회하며 미참여 시 404입니다.
GET `/api/v1/ai/analytics/profile`은 analyticsEnabled, categoryName, views, lastViewedAt, profileAsOf를 반환합니다.
프로필이 아직 없으면 categoryName은 null입니다. 이 응답은 알림 수신 설정을 변경하지 않습니다.
공식 이벤트: https://developers.google.com/analytics/devguides/collection/ga4/ecommerce
공식 사용자 ID: https://developers.google.com/analytics/devguides/collection/ga4/user-id

## 상품 공개와 후보 생성

Product Command의 `stockdrop.product.published.v1`은 운영자 최초 승인 트랜잭션에 Outbox로 저장됩니다.
초안 생성·심사 제출·반려·수정 심사 승인·재고·FAQ 변경은 이 이벤트를 생성하지 않습니다.
기존 승인 상품을 자동 백필하지 않습니다. 이벤트 스키마는 ProductPublishedEvent와 Published DTO를 따릅니다.
같은 상품의 공개 이벤트 ID는 고정되고 Product의 Query 스냅샷 이벤트와 독립된 계약입니다.

AI는 공개 이벤트를 먼저 영속화한 뒤 200명 단위로 후보를 만듭니다.
첫 분할 시 분석 회차를 고정하므로 중간에 일일 프로필이 바뀌어도 대상이 섞이거나 누락되지 않습니다.
같은 상품·사용자 후보는 DB 유일 제약과 고정 eventId로 중복 생성하지 않습니다.
해당 상품 판매자 본인과 종료된 상품은 제외합니다. 3일을 넘긴 통계는 신규 대상 선택에 쓰지 않습니다.
후보와 Outbox, 진행 커서는 같은 로컬 트랜잭션으로 저장하며 복제본은 SKIP LOCKED로 작업을 분배합니다.
최신 회차와 진행 중인 대상 분할이 참조하는 회차만 유지합니다.

후보 토픽: `stockdrop.ai.recommendation-candidates.v1`, 파티션 키: productId.
후보는 eventId, schemaVersion=1, type=PERSONALIZED_PRODUCT_CANDIDATE,
accountId, analyticsId, productId, productName, categoryCode, categoryName,
publishedAt, startsAt, endsAt, profileAsOf, occurredAt을 포함합니다.
AI의 소비 그룹은 ai-category-recommendations-v1이며 Product Query와 다른 그룹입니다.

## Notification 연결 시 필수 계약

Notification은 후보를 받은 것만으로 발송하면 안 됩니다. 다음을 발송 직전에 확인해야 합니다.

- Account 최신 상태가 활성이고 개인화 알림 설정과 광고 수신 동의가 유효한지 확인합니다.
- AI 내부 GET `/internal/v1/personalization/eligibility?accountId=...&analyticsId=...`에서 analyticsEnabled=true인지 확인합니다.
- 상품이 여전히 공개 상태이며 알림이 유효한 시각인지 확인합니다.
- 같은 eventId·계정·상품의 중복 발송을 방지하고 발송 상태·재시도를 영속 저장합니다.

AI 내부 API는 공개 Gateway에 라우팅하지 않습니다.
Account에서 발급한 별도 Notification 서비스 토큰의 scope=ai.personalization.read,
principal_type=service, client_id=notification-service, 지정 UUID subject를 모두 검증합니다.
Account 기존 서비스 토큰 로직의 scope 허용 목록만 확장했고 실제 Notification 클라이언트나 비밀값은 등록하지 않았습니다.
NOTIFICATION_SERVICE_SUBJECT 미설정 시 이 API는 차단됩니다. 사용자 JWT나 Order 토큰을 재사용하지 않습니다.
Account의 배치 수신 자격 확인 API는 아직 없으며 Notification 구현 시 계약을 추가해야 합니다.
여러 서비스의 동의 변경과 외부 발송이 단일 DB 트랜잭션으로 묶이는 구조라고 표현하지 않습니다.

## Google 설정과 비용 보호

GA4 속성에 웹 데이터 스트림과 BigQuery 일일 내보내기를 사용자가 설정해야 합니다.
AIService에는 다음 값을 주입합니다. 실제 값과 자격 파일은 Git에 올리지 않습니다.

```dotenv
AI_GA_ENABLED=true
AI_PERSONALIZATION_ENABLED=true
AI_OUTBOX_PUBLISHER_ENABLED=true
GA_BIGQUERY_PROJECT_ID=your-google-project
GA_BIGQUERY_DATASET=analytics_123456789
GA_BIGQUERY_LOCATION=asia-northeast3
GA_PROPERTY_TIME_ZONE=Asia/Seoul
```

데이터셋의 실제 위치를 사용하며 위 위치는 예시입니다. 측정 ID(G-...)는 프런트 설정이며 백엔드 비밀키가 아닙니다.
Google 자격은 ADC를 사용합니다. 로컬은 gcloud의 Application Default Credentials,
운영 AWS는 Workload Identity Federation을 권장하며 필요 시 Git 제외 .secrets 경로의 자격 파일을 사용합니다.
최소 권한은 조회 프로젝트의 BigQuery Job User와 해당 GA 데이터셋의 BigQuery Data Viewer입니다.
OpenAI 키나 AWS Access Key를 Google 자격으로 사용하지 않습니다.
공식 인증: https://docs.cloud.google.com/bigquery/docs/authentication

기본 maximumBytesBilled는 1 GiB, 최대 집계 행은 100000개입니다. 초과 시 결과를 잘라 프로필을 덮지 않고 실패 처리합니다.
Google 비용이 무료라고 보장하지 않습니다. 데이터량에 맞춰 운영자가 상한과 예산을 조정해야 합니다.
조회 날짜와 행 제한은 바인딩하고 테이블 식별자는 검증한 설정으로만 만듭니다.
GA 조회 SQL은 repository/impl과 analytics/category-views.sql에 한정하고 로컬 업무 DB는 JPA/@Query를 사용합니다.
실제 Google 데이터의 export 여부·SQL 실행·권한·정확도는 실계정 연동 전까지 검증 완료가 아닙니다.

## 로컬 준비와 검증

기존 공유 Kafka를 시작한 뒤 scripts/Initialize-LocalAiTopics.ps1로 신규 공개·후보 토픽을 준비합니다.
Product Command는 기존 PRODUCT_OUTBOX_PUBLISHER_ENABLED=true가 필요합니다.
새 기능은 기본 비활성이며 실제 .env와 Google 클라우드는 작업 중 변경하지 않았습니다.
테스트는 격리 PostgreSQL/Kafka와 BigQuery SDK 대역을 사용하고 실제 Google 과금 쿼리를 실행하지 않습니다.
