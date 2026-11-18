-- AI 서비스의 최초 테이블입니다. 엔티티 enum 값은 애플리케이션이 검증하므로 DB CHECK로 고정하지 않습니다.

create table ai.analysis_jobs (
    attempts integer not null,
    created_at timestamp(6) with time zone,
    lease_until timestamp(6) with time zone,
    next_attempt_at timestamp(6) with time zone,
    id uuid not null,
    lease_token uuid,
    product_id uuid not null,
    seller_id uuid not null,
    request_payload text not null,
    result_payload text,
    status varchar(255) not null,
    primary key (id)
);

create table ai.analytics_identities (
    consented_at timestamp(6) with time zone not null,
    account_id uuid not null,
    analytics_id uuid not null unique,
    primary key (account_id)
);

create table ai.category_interests (
    window_end date,
    last_viewed_at timestamp(6) with time zone,
    views bigint not null,
    analytics_id uuid not null,
    batch_id uuid not null,
    id uuid not null,
    identity_account_id uuid not null,
    category_name varchar(200) not null,
    primary key (id),
    unique (batch_id, identity_account_id)
);

create table ai.ga_sync_state (
    completed_through date,
    lease_until timestamp(6) with time zone,
    next_attempt_at timestamp(6) with time zone,
    active_batch_id uuid,
    lease_token uuid,
    id varchar(255) not null,
    primary key (id)
);

create table ai.outbox_events (
    attempts integer not null,
    created_at timestamp(6) with time zone not null,
    next_attempt_at timestamp(6) with time zone not null,
    published_at timestamp(6) with time zone,
    id uuid not null,
    partition_key varchar(255) not null,
    payload text not null,
    topic varchar(255) not null,
    primary key (id)
);

create table ai.published_products (
    completed boolean not null,
    profile_as_of date,
    published_at timestamp(6) with time zone not null,
    batch_id uuid,
    event_id uuid not null,
    last_analytics_id uuid,
    product_id uuid not null,
    payload text not null,
    primary key (product_id)
);

create table ai.recommendation_candidates (
    created_at timestamp(6) with time zone,
    account_id uuid not null,
    analytics_id uuid not null,
    id uuid not null,
    product_id uuid not null,
    primary key (id),
    unique (product_id, account_id)
);

create index idx_analysis_job_due
   on ai.analysis_jobs (status, next_attempt_at);

create index idx_interest_audience
   on ai.category_interests (batch_id, category_name, analytics_id);

create index idx_interest_account
   on ai.category_interests (identity_account_id);

create index idx_outbox_pending
   on ai.outbox_events (published_at, next_attempt_at, created_at);

create index idx_published_pending
   on ai.published_products (completed, published_at, product_id);

alter table if exists ai.category_interests
   add constraint FKpycstkeowdoxnm3anmcixj27u
   foreign key (identity_account_id)
   references ai.analytics_identities;
