package com.msa.ai.domain.behavior.repository;

import jakarta.persistence.EntityManager;

import org.springframework.stereotype.Repository;

import java.time.*;
import java.util.*;

/** 행동과 수집 카운터는 해당 계정의 분석 참여 잠금 아래 저장합니다. */
@Repository
public class BehaviorRepository {
    private final EntityManager em;

    public BehaviorRepository(EntityManager em) {
        this.em = em;
    }

    public boolean duplicate(UUID id, String hash) {
        var prior =
                em.createNativeQuery(
                                "select payload_hash from ai.behavior_events where id=:id",
                                String.class)
                        .setParameter("id", id)
                        .getResultList();
        if (prior.isEmpty()) return false;
        if (!hash.equals(prior.getFirst()))
            throw new com.msa.ai.domain.behavior.exception.BehaviorException(
                    com.msa.ai.domain.behavior.exception.BehaviorErrorCode.INVALID);
        return true;
    }

    public boolean reserve(UUID account, LocalDate day) {
        return !em.createNativeQuery(
                        "insert into ai.behavior_daily_counters(account_id,day,count)"
                                + " values(:a,:d,1) on conflict(account_id,day) do update set"
                                + " count=ai.behavior_daily_counters.count+1 where"
                                + " ai.behavior_daily_counters.count<2000 returning count",
                        Integer.class)
                .setParameter("a", account)
                .setParameter("d", day)
                .getResultList()
                .isEmpty();
    }

    public void insert(
            UUID id,
            UUID account,
            UUID analytics,
            String name,
            UUID product,
            String term,
            String source,
            Instant occurred,
            Instant now,
            String hash) {
        em.createNativeQuery(
                        "insert into"
                            + " ai.behavior_events(id,account_id,analytics_id,name,product_id,search_term,source,occurred_at,received_at,payload_hash)"
                            + " values(:id,:a,:i,:n,:p,:t,:s,:o,:now,:hash) on conflict(id) do"
                            + " nothing")
                .setParameter("id", id)
                .setParameter("a", account)
                .setParameter("i", analytics)
                .setParameter("n", name)
                .setParameter("p", product)
                .setParameter("t", term)
                .setParameter("s", source)
                .setParameter("o", occurred)
                .setParameter("now", now)
                .setParameter("hash", hash)
                .executeUpdate();
    }

    public void purge(Instant before) {
        em.createNativeQuery("delete from ai.behavior_events where occurred_at<:before")
                .setParameter("before", before)
                .executeUpdate();
        em.createNativeQuery("delete from ai.behavior_daily_counters where day<:d")
                .setParameter("d", before.atZone(ZoneId.of("Asia/Seoul")).toLocalDate())
                .executeUpdate();
    }
}
