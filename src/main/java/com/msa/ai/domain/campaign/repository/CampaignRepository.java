package com.msa.ai.domain.campaign.repository;

import jakarta.persistence.EntityManager;

import org.springframework.stereotype.Repository;

import java.util.*;

/** PostgreSQL 조건부 갱신과 lease SQL을 도메인 저장소에 모읍니다. */
@Repository
public class CampaignRepository {
    private final EntityManager em;

    public CampaignRepository(EntityManager em) {
        this.em = em;
    }

    public int update(String sql, Map<String, Object> params) {
        var q = em.createNativeQuery(sql);
        params.forEach(q::setParameter);
        return q.executeUpdate();
    }

    public List<?> query(String sql, Map<String, Object> params) {
        var q = em.createNativeQuery(sql);
        params.forEach(q::setParameter);
        return q.getResultList();
    }
}
