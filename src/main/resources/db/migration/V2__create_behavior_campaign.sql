-- 분석 철회와 저장을 참여 행 잠금으로 직렬화하고 원문은 함께 삭제합니다.
create table ai.behavior_events(id uuid primary key, account_id uuid not null references ai.analytics_identities(account_id) on delete cascade, analytics_id uuid not null,name varchar(32) not null,product_id uuid,search_term varchar(50),source varchar(8) not null,payload_hash varchar(64),occurred_at timestamptz not null,received_at timestamptz not null);
create index idx_behavior_account on ai.behavior_events(account_id,name,occurred_at);
create index idx_behavior_product on ai.behavior_events(name,product_id,occurred_at);
create index idx_behavior_retention on ai.behavior_events(occurred_at);
create table ai.behavior_daily_counters(account_id uuid not null references ai.analytics_identities(account_id) on delete cascade,day date not null,count int not null,primary key(account_id,day));
create table ai.campaign_rules(rule_code varchar(32) primary key,enabled boolean not null default true,next_run_at timestamptz not null default now(),lease_token uuid,lease_until timestamptz,tick bigint);
insert into ai.campaign_rules(rule_code) values('ALL'),('SALE_OPEN'),('CHECKOUT_ABANDON'),('SOLD_OUT_SIMILAR'),('CATEGORY_NEW'),('REPEAT_VIEW'),('SEARCH_MATCH');
create table ai.campaign_decisions(id uuid primary key,account_id uuid not null references ai.analytics_identities(account_id) on delete cascade,analytics_id uuid not null,rule_code varchar(32) not null,product_id uuid not null,coupon_required boolean not null,campaign_code varchar(100),status varchar(16) not null,day_kst date not null,tick bigint,created_at timestamptz not null);
create index idx_decision_account on ai.campaign_decisions(account_id,created_at);
create unique index idx_decision_tick on ai.campaign_decisions(account_id,tick) where tick is not null;
create table ai.campaign_daily_counters(account_id uuid not null references ai.analytics_identities(account_id) on delete cascade,day_kst date not null,count int not null,primary key(account_id,day_kst));
alter table ai.outbox_events add column failed_at timestamptz;
