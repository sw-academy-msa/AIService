package com.msa.ai.domain.campaign.service.impl;

import com.msa.ai.domain.campaign.dto.response.CampaignCandidate;
import com.msa.ai.domain.campaign.repository.CampaignRepository;
import com.msa.ai.domain.campaign.service.CampaignStore;
import com.msa.ai.domain.outbox.entity.OutboxEvent;
import com.msa.ai.domain.outbox.repository.OutboxEventRepository;
import com.msa.ai.domain.personalization.dto.response.PersonalizationMessages.Published;
import com.msa.ai.domain.personalization.repository.AnalyticsIdentityRepository;
import com.msa.ai.global.config.campaign.CampaignProperties;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

/** lease와 참여 행 잠금으로 중복 결정과 철회 경쟁을 막고 결정과 카운터 및 Outbox를 함께 기록합니다. */
@Service
@Transactional(timeout = 5)
public class CampaignStoreImpl implements CampaignStore {
    private final CampaignRepository db;
    private final AnalyticsIdentityRepository identities;
    private final OutboxEventRepository outbox;
    private final CampaignProperties config;
    private final JsonMapper json;
    private final Clock clock;

    public CampaignStoreImpl(
            CampaignRepository db,
            AnalyticsIdentityRepository identities,
            OutboxEventRepository outbox,
            CampaignProperties config,
            JsonMapper json,
            Clock clock) {
        this.db = db;
        this.identities = identities;
        this.outbox = outbox;
        this.config = config;
        this.json = json;
        this.clock = clock;
    }

    private boolean daylight() {
        int h = clock.instant().atZone(ZoneId.of("Asia/Seoul")).getHour();
        return config.demo() || h >= 8 && h < 21;
    }

    public UUID claim() {
        if (!config.enabled() || !daylight()) return null;
        UUID token = UUID.randomUUID();
        Instant now = clock.instant();
        int count =
                db.update(
                        "update ai.campaign_rules set lease_token=:t,lease_until=:until,tick=:tick"
                            + " where rule_code in (select rule_code from ai.campaign_rules where"
                            + " rule_code='ALL' and enabled and (next_run_at<=:now or (:demo and"
                            + " next_run_at>:horizon)) and (lease_until is null or"
                            + " lease_until<=:now) for update skip locked)",
                        Map.of(
                                "t",
                                token,
                                "until",
                                now.plusSeconds(110),
                                "now",
                                now,
                                "tick",
                                now.getEpochSecond() / 120,
                                "demo",
                                config.demo(),
                                "horizon",
                                now.plusSeconds(120)));
        if (count == 1) {
            db.update(
                    "update ai.campaign_rules set lease_token=:t,lease_until=:until where"
                        + " rule_code<>'ALL' and enabled and (:demo or next_run_at<=:now) and"
                        + " (lease_until is null or lease_until<=:now) and (:daily or rule_code in"
                        + " ('SALE_OPEN','CHECKOUT_ABANDON'))",
                    Map.of(
                            "t",
                            token,
                            "until",
                            now.plusSeconds(110),
                            "now",
                            now,
                            "daily",
                            config.demo() || now.atZone(ZoneId.of("Asia/Seoul")).getHour() == 10,
                            "demo",
                            config.demo()));
        }
        return count == 1 ? token : null;
    }

    public List<CampaignCandidate> candidates(UUID token) {
        if (!owned(token)) return List.of();
        Instant now = clock.instant();
        var kst = now.atZone(ZoneId.of("Asia/Seoul"));
        boolean daily = config.demo() || kst.getHour() == 10;
        var params = new HashMap<String, Object>();
        params.put("now", now);
        params.put("viewsAfter", now.minusSeconds(config.demo() ? 600 : 86400));
        params.put("viewCount", config.demo() ? 2 : 3);
        params.put("abandonBefore", now.minusSeconds(config.demo() ? 0 : 1800));
        params.put("soldAfter", now.minus(Duration.ofDays(config.demo() ? 1 : 14)));
        params.put("searchAfter", now.minus(Duration.ofDays(config.demo() ? 1 : 7)));
        params.put("daily", daily);
        params.put("demo", config.demo());
        params.put("ids", config.demo() ? config.accounts() : Set.of(new UUID(0, 0)));
        params.put("day", kst.toLocalDate());
        params.put("token", token);
        params.put("cap", config.demo() ? 20 : 2);
        params.put("perAccount", config.demo() ? 1 : 2);
        // 마지막 관심 등록, 주문 이탈, 조회 빈도와 공개 상품을 현재 동의 가명에만 연결합니다.
        String sql =
                """
                with products as (select product_id,cast(payload as jsonb) p,payload from ai.published_products), audience as (
                select i.account_id,i.analytics_id,p.payload,p.product_id,
                case
                when cast(p.p->>'startsAt' as timestamptz)<=:now and cast(p.p->>'startsAt' as timestamptz)>cast(:now as timestamptz)-interval '30 minutes'
                  and exists(select 1 from ai.behavior_events w where w.account_id=i.account_id and w.product_id=p.product_id and w.name='add_to_wishlist' and not exists(select 1 from ai.behavior_events x where x.account_id=i.account_id and x.product_id=p.product_id and x.name='remove_from_wishlist' and x.occurred_at>=w.occurred_at)) then 'SALE_OPEN'
                when exists(select 1 from ai.behavior_events a where a.account_id=i.account_id and a.product_id=p.product_id and a.name='checkout_abandoned' and a.occurred_at<=:abandonBefore and exists(select 1 from ai.behavior_events h where h.account_id=i.account_id and h.product_id=p.product_id and h.name='begin_checkout' and h.occurred_at<=a.occurred_at) and not exists(select 1 from ai.behavior_events u where u.account_id=i.account_id and u.product_id=p.product_id and u.name='purchase' and u.occurred_at>=a.occurred_at)) then 'CHECKOUT_ABANDON'
                when :daily and cast(p.p->>'startsAt' as timestamptz)<=:now and exists(select 1 from ai.behavior_events s join products old on old.product_id=s.product_id where s.account_id=i.account_id and s.name='sold_out_view' and s.occurred_at>=:soldAfter and old.p->>'categoryCode'=p.p->>'categoryCode' and s.product_id<>p.product_id and s.occurred_at<=cast(p.p->>'startsAt' as timestamptz)) then 'SOLD_OUT_SIMILAR'
                when :daily and ((not :demo and exists(select 1 from ai.category_interests c join ai.ga_sync_state g on g.active_batch_id=c.batch_id where g.id='ga4' and g.completed_through>=cast(:day as date)-3 and c.identity_account_id=i.account_id and c.category_name=p.p->>'categoryName')) or (:demo and p.p->>'categoryCode'=(select v.p->>'categoryCode' from ai.behavior_events b join products v on v.product_id=b.product_id where b.account_id=i.account_id and b.name='view_item' and b.occurred_at>=cast(:now as timestamptz)-interval '24 hours' and b.occurred_at<=cast(p.p->>'publishedAt' as timestamptz) group by v.p->>'categoryCode' order by count(*) desc,v.p->>'categoryCode' limit 1))) then 'CATEGORY_NEW'
                when :daily and cast(p.p->>'startsAt' as timestamptz)<=:now and (select count(*) from ai.behavior_events v where v.account_id=i.account_id and v.product_id=p.product_id and v.name='view_item' and v.occurred_at>=:viewsAfter)>=:viewCount and not exists(select 1 from ai.behavior_events u where u.account_id=i.account_id and u.product_id=p.product_id and u.name='purchase') then 'REPEAT_VIEW'
                when :daily and exists(select 1 from ai.behavior_events s where s.account_id=i.account_id and s.name='search' and s.occurred_at>=:searchAfter and length(s.search_term)>=2 and position(s.search_term in lower(p.p->>'productName'))>0 and s.occurred_at<=cast(p.p->>'publishedAt' as timestamptz)) then 'SEARCH_MATCH'
                end rule_code
                from ai.analytics_identities i cross join products p
                where (not :demo or i.account_id in (:ids)) and i.account_id<>cast(p.p->>'sellerId' as uuid) and cast(p.p->>'endsAt' as timestamptz)>:now
                ), eligible as (
                select a.*,case a.rule_code when 'SALE_OPEN' then 1 when 'CHECKOUT_ABANDON' then 2 when 'SOLD_OUT_SIMILAR' then 3 when 'CATEGORY_NEW' then 4 when 'REPEAT_VIEW' then 5 else 6 end priority
                from audience a join ai.campaign_rules r on r.rule_code=a.rule_code
                where a.rule_code is not null and r.enabled and r.lease_token=:token
                and not exists(select 1 from ai.campaign_decisions d where d.account_id=a.account_id and d.product_id=a.product_id and (d.rule_code<>'REPEAT_VIEW' or d.created_at>cast(:now as timestamptz)-interval '7 days'))
                and not exists(select 1 from ai.recommendation_candidates c where c.account_id=a.account_id and c.product_id=a.product_id)
                and (select coalesce(max(count),0) from ai.campaign_daily_counters dc where dc.account_id=a.account_id and dc.day_kst=:day)<:cap
                ), ranked as (
                select e.*,row_number() over(partition by account_id order by priority,product_id) audience_rank from eligible e
                ) select account_id,analytics_id,payload,rule_code from ranked where audience_rank<=:perAccount
                order by priority,account_id,product_id limit 1000
                """;
        var result = new ArrayList<CampaignCandidate>();
        for (Object raw : db.query(sql, params)) {
            Object[] a = (Object[]) raw;
            result.add(
                    new CampaignCandidate(
                            (UUID) a[0],
                            (UUID) a[1],
                            (String) a[3],
                            json.readValue((String) a[2], Published.class)));
        }
        return result;
    }

    public boolean emit(UUID token, CampaignCandidate c) {
        if (!owned(token)
                || !daylight()
                || config.demo() && !config.accounts().contains(c.accountId())) return false;
        var identity = identities.lock(c.accountId());
        if (identity.isEmpty() || !identity.get().analyticsId.equals(c.analyticsId())) return false;
        Instant now = clock.instant();
        var day = now.atZone(ZoneId.of("Asia/Seoul")).toLocalDate();
        long tick =
                ((Number)
                                db.query(
                                                "select tick from ai.campaign_rules where"
                                                        + " rule_code='ALL' and lease_token=:t",
                                                Map.of("t", token))
                                        .getFirst())
                        .longValue();
        UUID product = c.product().productId();
        String rule = c.ruleCode();
        String subject = product + ("REPEAT_VIEW".equals(rule) ? ":" + day.toEpochDay() / 7 : "");
        UUID id =
                UUID.nameUUIDFromBytes(
                        ("CATEGORY_NEW".equals(rule)
                                        ? "category-candidate-v1:" + product + ":" + c.accountId()
                                        : rule + ":" + subject + ":" + c.accountId())
                                .getBytes(StandardCharsets.UTF_8));
        if (!db.query(
                        "select 1 from ai.campaign_decisions where id=:id or (account_id=:a and"
                                + " product_id=:p and (rule_code<>'REPEAT_VIEW' or"
                                + " created_at>cast(:now as timestamptz)-interval '7 days'))",
                        Map.of("id", id, "a", c.accountId(), "p", product, "now", now))
                .isEmpty()) return false;
        if (config.demo()
                && !db.query(
                                "select 1 from ai.campaign_decisions where account_id=:a and"
                                        + " tick=:t",
                                Map.of("a", c.accountId(), "t", tick))
                        .isEmpty()) return false;
        boolean coupon =
                Set.of("SALE_OPEN", "CHECKOUT_ABANDON", "SOLD_OUT_SIMILAR", "CATEGORY_NEW")
                        .contains(rule);
        // 기존 후보와 같은 유니크 키를 먼저 확보해 NORMAL/OFF 복제본이 섞여도 둘 중 하나만 발행합니다.
        boolean categoryGate = "CATEGORY_NEW".equals(rule);
        if (categoryGate
                && db.update(
                                "insert into"
                                    + " ai.recommendation_candidates(id,account_id,analytics_id,product_id,created_at)"
                                    + " values(:id,:a,:i,:p,:now) on"
                                    + " conflict(product_id,account_id) do nothing",
                                Map.of(
                                        "id",
                                        id,
                                        "a",
                                        c.accountId(),
                                        "i",
                                        c.analyticsId(),
                                        "p",
                                        product,
                                        "now",
                                        now))
                        == 0) return false;
        var params = new HashMap<String, Object>();
        params.put("a", c.accountId());
        params.put("d", day);
        params.put("cap", config.demo() ? 20 : 2);
        if (db.query(
                        "insert into ai.campaign_daily_counters(account_id,day_kst,count)"
                                + " values(:a,:d,1) on conflict(account_id,day_kst) do update set"
                                + " count=ai.campaign_daily_counters.count+1 where"
                                + " ai.campaign_daily_counters.count<:cap returning count",
                        params)
                .isEmpty()) {
            // 상한으로 발행하지 않은 후보는 같은 트랜잭션 안에서 원복해 다음 날의 판단을 막지 않습니다.
            if (categoryGate)
                db.update(
                        "delete from ai.recommendation_candidates where id=:id", Map.of("id", id));
            return false;
        }
        String campaign = rule + (config.demo() ? "_DEMO" : "_NORMAL");
        params = new HashMap<>();
        params.put("id", id);
        params.put("a", c.accountId());
        params.put("i", c.analyticsId());
        params.put("r", rule);
        params.put("p", product);
        params.put("c", coupon);
        params.put("code", campaign);
        params.put("d", day);
        params.put("tick", config.demo() ? tick : null);
        params.put("now", now);
        db.update(
                "insert into"
                    + " ai.campaign_decisions(id,account_id,analytics_id,rule_code,product_id,coupon_required,campaign_code,status,day_kst,tick,created_at)"
                    + " values(:id,:a,:i,:r,:p,:c,:code,'EMITTED',:d,:tick,:now)",
                params);
        Map<String, Object> payload = new LinkedHashMap<>();
        UUID eventId = id;
        String topic;
        if (coupon) {
            topic = "stockdrop.ai.campaign-targets.v1";
            payload.put("type", "CAMPAIGN_TARGET");
            payload.put("ruleCode", rule);
            payload.put("campaignCode", campaign);
            payload.put("productId", product);
            payload.put("subjectKey", product.toString());
            payload.put("demo", config.demo());
        } else {
            topic = "stockdrop.notifications.campaign.v1";
            String type =
                    "REPEAT_VIEW".equals(rule)
                            ? "CAMPAIGN_STOCK_REMINDER"
                            : "CAMPAIGN_SEARCH_MATCH";
            eventId =
                    UUID.nameUUIDFromBytes(
                            ("CAMPAIGN:" + type + ":" + id + ":1:" + c.accountId())
                                    .getBytes(StandardCharsets.UTF_8));
            payload.put("type", "BUSINESS_NOTIFICATION");
            payload.put("source", "CAMPAIGN");
            payload.put("notificationType", type);
            payload.put("aggregateId", id);
            payload.put("aggregateVersion", 1);
            payload.put("targetType", "PRODUCT");
            payload.put("targetId", product);
            payload.put("relatedId", null);
        }
        payload.put("eventId", eventId);
        payload.put("schemaVersion", 1);
        payload.put("accountId", c.accountId());
        payload.put("deadline", c.product().endsAt());
        payload.put("occurredAt", now);
        outbox.save(
                OutboxEvent.builder()
                        .id(eventId)
                        .topic(topic)
                        .productId(c.accountId())
                        .payload(json.writeValueAsString(payload))
                        .now(now)
                        .build());
        return true;
    }

    private boolean owned(UUID token) {
        return !db.query(
                        "select 1 from ai.campaign_rules where rule_code='ALL' and lease_token=:t"
                                + " and lease_until>:now for update",
                        Map.of("t", token, "now", clock.instant()))
                .isEmpty();
    }

    public void complete(UUID token) {
        if (!owned(token)) return;
        Instant now = clock.instant();
        long interval = config.demo() ? 120 : 600;
        Instant next = Instant.ofEpochSecond((now.getEpochSecond() / interval + 1) * interval);
        var kst = now.atZone(ZoneId.of("Asia/Seoul"));
        Instant daily =
                kst.toLocalDate()
                        .plusDays(kst.getHour() >= 10 ? 1 : 0)
                        .atTime(10, 0)
                        .atZone(ZoneId.of("Asia/Seoul"))
                        .toInstant();
        db.update(
                "update ai.campaign_rules set lease_token=null,lease_until=null,next_run_at=case"
                        + " when :demo or rule_code in ('ALL','SALE_OPEN','CHECKOUT_ABANDON') then"
                        + " cast(:next as timestamptz) else cast(:daily as timestamptz) end where"
                        + " lease_token=:t",
                Map.of("t", token, "next", next, "daily", daily, "demo", config.demo()));
    }
}
