# 상품 등록 AI 계약 v1

요청: stockdrop.product.analysis-requested.v1, 결과: stockdrop.ai.product-analyzed.v1.
파티션 키는 productId, 요청/결과 멱등 식별자는 requestId입니다.
Product의 입력과 기준표는 요청 Outbox와 같은 트랜잭션으로 저장합니다.
AI의 작업 결과와 결과 Outbox도 AI DB의 같은 트랜잭션으로 저장합니다.
Kafka ack 후 발행 완료를 기록하므로 중복 발행은 가능하며 수신자가 같은 requestId를 재처리하지 않습니다.
다른 소비 목적은 독립 그룹, 같은 앱의 복제본은 같은 그룹을 사용합니다.

## 화면 호출

1. 구매 Gateway POST /api/v1/seller/products/{productId}/ai-analyses, expectedVersion 전달, HTTP 202.
2. 일반 Gateway GET /api/v1/ai/product-analyses/{requestId}, SELLER JWT로 본인 결과 조회.
   Kafka 수신 전에는 404일 수 있으므로 짧은 간격으로 재조회합니다.
3. 판매자가 추천 카테고리를 직접 선택하고 질문에 답변합니다.
4. 기존 Product Command FAQ POST/PUT에 question, answer, confirmed:true, suggestionId를 전달합니다.
5. 승인 상품의 확인 FAQ는 기존 Product Outbox → Kafka → Product Query의 상품 FAQ로 반영됩니다.

FAQ 저장은 Product가 결과 이벤트를 수신한 뒤 가능합니다. AI 완료와 Product 결과 반영 사이 지연이 가능합니다.
질문 참조는 본인 상품·현재 이미지/기본정보/처분 사유에 연결된 실제 결과여야 합니다.
추천 카테고리만 수락한 변경은 허용하지만 설명이나 이미지 변경 시 다시 분석해야 합니다.
상품 버전이 다른 단계 저장으로만 바뀐 경우 입력 지문이 같으면 질문을 재사용할 수 있습니다.
AI 질문이 없는 경우도 정상 완료이며 FAQ는 필수 등록 항목이 아닙니다.
사진이 불명확하거나 허용 범위 밖이면 recommendedCategory:null과 빈 질문으로 반환하며 판매자가 이미지를 보완하고 직접 분류합니다.
AI 결과는 심사 상태를 변경하지 않고 판매자 답변도 운영자 승인을 대신하지 않습니다.

## 입력과 제한

이미지, 기본정보(상품명·설명·브랜드/스토어·기존 판매처 URL·선택 카테고리), 재고 처분 사유만 모델에 제공합니다.
판매처 URL은 텍스트 참고이며 외부 페이지를 조회하지 않습니다.
배송 주소, 구매자·판매자 개인정보, 결제·주문 데이터는 전달하지 않습니다.
이미지는 Product가 소유권·형식을 검증한 product-images/{productId}/{imageId} 객체만 전달합니다.
AI는 지정된 단일 버킷에서 읽기만 하며 임의 URL·버킷을 받지 않습니다.
장당 10 MiB, 최대 10장, 분석 전체 원본 이미지 30 MiB, 이벤트 128 KiB의 기술 상한을 둡니다.
모델 기본값은 gpt-6.1-sol, Responses API의 이미지 입력과 strict JSON Schema를 사용하고 store:false를 전달합니다.
OpenAI 문서: https://developers.openai.com/api/docs/guides/images-vision
Structured Outputs: https://developers.openai.com/api/docs/guides/structured-outputs

## 장애와 보안

모델 실패는 RETRY로 영속 보관하고 지연을 늘려 최대 1시간 간격으로 재시도합니다.
임대 10분은 작업자 동시 실행을 막는 기술 설정이며 구매 재고의 5분 확보와 무관합니다.
만료된 임대와 다른 임대 토큰의 결과는 저장하지 않습니다.
모델 성공 후 DB 저장 전 장애에는 모델 호출과 비용이 중복될 수 있습니다.
잘못된 Kafka 메시지는 자동 폐기하지 않고 파티션 처리를 재시도하므로 운영 격리 절차가 필요합니다.
운영 Kafka는 private 연결과 생산자/소비자 ACL을 구성하고 AI IAM은 product-images 접두사 읽기로 제한합니다.
키·모델 요청 본문·판매자 설명·이미지 데이터·공급자 오류 원문을 로그에 남기지 않습니다.

GA 기반 관심도와 신규 상품 알림 후보는 별도 [개인화 계약](ga-category-recommendations-v1.md)에서 관리합니다.
구매자 실시간 AI 채팅, 자동 FAQ 공개, 자동 상품 승인은 범위에서 제외합니다.
