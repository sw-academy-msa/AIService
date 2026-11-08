package com.msa.ai.domain.analysis.repository;

import com.msa.ai.domain.analysis.entity.AnalysisJob;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.*;

import java.time.Instant;
import java.util.*;

/** SQL은 저장소에 한정합니다. SKIP LOCKED는 복제본의 동일 작업 동시 점유를 방지합니다. */
public interface AnalysisJobRepository extends JpaRepository<AnalysisJob, UUID> {
    @Modifying
    @Query(
            value =
                    """
insert into ai.analysis_jobs(id,product_id,seller_id,request_payload,status,attempts,next_attempt_at,created_at)
values (:id,:product,:seller,:payload,'PENDING',0,:now,:now) on conflict (id) do nothing
""",
            nativeQuery = true)
    int insertOnce(UUID id, UUID product, UUID seller, String payload, Instant now);

    @Query(
            value =
                    """
select * from ai.analysis_jobs where result_payload is null and next_attempt_at <= :now
and (lease_until is null or lease_until <= :now) order by created_at,id
limit 1 for update skip locked
""",
            nativeQuery = true)
    Optional<AnalysisJob> next(Instant now);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select j from AnalysisJob j where j.id=:id")
    Optional<AnalysisJob> lock(UUID id);
}
