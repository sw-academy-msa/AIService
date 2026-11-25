# 맞춤 쿠폰 캠페인 운영

## 기본 상태와 계약
AI_CAMPAIGN_MODE=OFF, AI_BEHAVIOR_ENABLED=false, AI_BEHAVIOR_LIFECYCLE_ENABLED=false가 기본입니다. 기존 AI_GA_ENABLED 및 AI_PERSONALIZATION_ENABLED와 30일 BigQuery 관심 프로필은 유지합니다. 모드 OFF에서는 기존 후보 경로가 동작하고 NORMAL 또는 DEMO에서는 캠페인이 같은 CATEGORY_NEW 결정 ID를 사용하며 기존 후보 발행을 멈춥니다. 과거 후보가 있는 계정과 상품은 캠페인 대상에서 제외합니다.

POST /api/v1/ai/events는 BUYER JWT 주체만 받습니다. events 최대 20건, 직렬화 본문 최대 8KiB이며 분석 미동의도 동일한 202를 돌려줍니다. view_item_list는 받기만 합니다. 검색어는 공백과 소문자를 정규화하고 50자로 제한하며 응답과 로그에는 넣지 않습니다. 수신 시각 10분 밖의 occurredAt은 수신 시각으로 보정합니다. 계정당 하루 2000건은 DB 카운터로 제한하며 초과는 ai.behavior.dropped 지표로 집계합니다. 매일 04:00 KST에 30일이 지난 행동과 수집 카운터를 삭제합니다. 분석 참여 행 삭제는 FK cascade로 행동, 결정, 카운터를 함께 지웁니다.

주문 입력 stockdrop.orders.lifecycle.v1은 ai-behavior-v1 그룹입니다. HELD는 begin_checkout, CONFIRMED는 purchase, FAILED 또는 EXPIRED이며 paymentStarted=false인 경우 checkout_abandoned입니다. eventId와 key 및 버전을 검증하고 저장된 같은 이벤트 본문 hash 충돌은 계약 오류로 격리합니다.

## 설정

- AI_CLIENT_SECRET, AI_SERVICE_SUBJECT: Account에 등록한 ai-service 자격 증명. scope는 account.notifications.read입니다.
- AI_CLIENT_SCHEME: 기본 https. 사설망 http 배포만 AI_CLIENT_ALLOW_PLAIN_HTTP=true와 함께 http를 명시합니다.
- PAYMENT_SERVICE_SUBJECT, NOTIFICATION_SERVICE_SUBJECT: 분석 동의 내부 조회를 허용하는 별도 서비스 주체. client_id 및 ai.personalization.read scope도 검증합니다.
- AI_OUTBOX_PUBLISHER_ENABLED: 기존 Outbox 발행 플래그입니다.
- AI_CAMPAIGN_MODE: OFF, NORMAL, DEMO. 환경 변경은 재시작 후 적용합니다.
- CAMPAIGN_DEMO_ACCOUNT_IDS: 쉼표로 구분한 UUID 최대 10개. 비어 있거나 잘못되면 DEMO는 OFF로 내려가고 오류 로그를 냅니다.

## 계산과 발행

DB의 ALL 조정 lease와 각 규칙 lease가 같은 회차를 보호합니다. HTTP 동의 조회는 TX 밖이며 결정 시 분석 참여 잠금을 다시 확인합니다. 우선순위는 SALE_OPEN, CHECKOUT_ABANDON, SOLD_OUT_SIMILAR, CATEGORY_NEW, REPEAT_VIEW, SEARCH_MATCH입니다. 본인 판매 상품과 과거 후보 및 중복 결정을 제외합니다. 일반 규칙은 상품당 한 번, REPEAT_VIEW는 7일 간격입니다. 카운터, 결정, Outbox는 같은 TX입니다. 캠페인 대상과 알림의 Kafka key는 accountId입니다.

NORMAL은 사람당 하루 2건입니다. 규칙 1, 2는 10분 간격, 나머지는 10:00 KST 회차입니다. 21:00부터 08:00까지 계산하지 않으며 Notification에서도 다시 막습니다. DEMO는 2분 회차, 계정당 회차 1건, 하루 20건이며 SQL에서부터 허용 목록만 선택합니다. 회차 키는 claim 시 고정해 처리 중 시간 경계가 지나도 같은 회차 상한을 유지합니다.

쿠폰 규칙은 stockdrop.ai.campaign-targets.v1으로 CouponService에 요청합니다. 캠페인 code는 SALE_OPEN_NORMAL, CHECKOUT_ABANDON_NORMAL, SOLD_OUT_SIMILAR_NORMAL, CATEGORY_NEW_NORMAL이며 시연은 접미사가 _DEMO입니다. 운영자는 같은 code와 ruleCode 및 demo 값을 가진 ACTIVE TARGETED 캠페인을 먼저 생성합니다. CouponService가 발급 및 예산 소진 대체 알림을 담당합니다. REPEAT_VIEW는 CAMPAIGN_STOCK_REMINDER, SEARCH_MATCH는 CAMPAIGN_SEARCH_MATCH를 stockdrop.notifications.campaign.v1으로 보냅니다.

새 입력은 1초 간격 3회 재시도 후 원 partition DLT, 계약 오류는 즉시 DLT입니다. DLT ack가 실패하면 listener를 정지하고 offset을 넘기지 않습니다. 새 캠페인 Outbox는 20회 실패 후 failed_at으로 격리합니다. 원 eventId를 유지한 운영자 재처리만 허용합니다.

## 2분 시연

1. 테스트 계정 A의 분석 참여와 마케팅 동의, 맞춤 추천, Web Push 구독을 켭니다. shared 허용 목록과 demo=true 캠페인을 AI, Coupon, Notification에 동일하게 설정합니다.
2. 시작 직전 상품 P1의 add_to_wishlist를 수집하고 P1 판매 시작을 맞춥니다. 첫 tick에는 SALE_OPEN 쿠폰 대상 한 건이 나갑니다.
3. 다음 tick 전 다른 상품의 같은 카테고리 sold_out_view와 새 상품 P2를 준비하면 SOLD_OUT_SIMILAR가 우선 처리됩니다. 기존 GA는 그대로 두며 DEMO CATEGORY_NEW만 최근 24시간 view 최다 카테고리를 사용합니다.
4. 반복 조회는 10분 안 2건, 검색은 하루 안 2자 이상입니다. 같은 tick에 여러 조건을 준비하면 1, 2, 4, 6, 3, 5 순서로 다른 대상이 뒤로 밀립니다.
5. 주문 이탈 규칙은 실제 주문 확보 5분 뒤 만료 이벤트가 필요합니다. 시작 후 2분만으로 규칙 2를 시연하려면 시작 전에 만료 주문을 준비해야 합니다. NORMAL은 만료 30분 뒤입니다.
6. 끝나면 AI_CAMPAIGN_MODE=OFF로 재시작하고 demo 캠페인을 pause합니다. 쿠폰 만료 임박과 회원 쿠폰은 CouponService의 별도 이벤트이며 tick과 독립입니다.

## 배포와 되돌리기

Infra 토픽 및 DLT 준비 후 Notification, Account 서비스 권한, CouponService 순서로 배포합니다. AI는 행동 플래그만 켜고 모드는 OFF로 시작합니다. 주문 lifecycle과 Gateway /api/v1/ai/events 라우트를 준비한 다음 FE 이중 전송을 연결합니다. 격리 계정 확인 후 DEMO 또는 NORMAL을 별도 승인으로 켭니다. query 서버는 한 대씩 재시작합니다.

중단 시 모드를 OFF로 되돌리고 캠페인을 pause합니다. 행동 수집도 중단하려면 두 행동 플래그를 false로 둡니다. Outbox와 토픽 및 Flyway V2는 보존하고 down migration은 하지 않습니다. 코드 복구는 머지 커밋을 revert하는 PR입니다. 주문 쿠폰 사용 중단과 잠금 복구는 CouponService 및 order-command의 운영 절차를 따릅니다.

계산 한 묶음은 최대 1000개 후보와 110초 lease로 제한합니다. 대규모 대상에서는 후보 수와 회차 처리 시간을 확인하고 별도 부하 검증 후 활성화합니다. 실제 Google 조회 성공 여부, 서비스 간 E2E, 운영 배포, 실결제와 FE 이벤트 및 쿠폰 화면은 이 테스트에 포함하지 않습니다.
