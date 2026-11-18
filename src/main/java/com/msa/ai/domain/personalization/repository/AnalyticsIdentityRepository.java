package com.msa.ai.domain.personalization.repository;

import com.msa.ai.domain.personalization.entity.AnalyticsIdentity;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.*;

import java.time.Instant;
import java.util.*;

/** 가명 식별자 생성은 회원별 유일 제약으로 멱등하게 처리합니다. */
public interface AnalyticsIdentityRepository extends JpaRepository<AnalyticsIdentity, UUID> {
    @Modifying
    @Query(
            value =
                    """
                    insert into ai.analytics_identities(account_id,analytics_id,consented_at)
                    values (:account,:analytics,:now) on conflict (account_id) do nothing
                    """,
            nativeQuery = true)
    int insertOnce(UUID account, UUID analytics, Instant now);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from AnalyticsIdentity i where i.accountId=:account")
    Optional<AnalyticsIdentity> lock(UUID account);

    @Query("select i from AnalyticsIdentity i where i.analyticsId in :ids")
    List<AnalyticsIdentity> matching(Collection<UUID> ids);

    @Query(
            "select count(i) from AnalyticsIdentity i where i.analyticsId=:analytics and"
                + " i.accountId=:account")
    long active(UUID analytics, UUID account);
}
