package com.msa.ai.domain.personalization.repository;

import com.msa.ai.domain.personalization.entity.CategoryInterest;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;

import java.util.*;

/** AI 자기 조회 모델만 조회하며 Account나 Product 테이블과 JOIN하지 않습니다. */
public interface CategoryInterestRepository extends JpaRepository<CategoryInterest, UUID> {
    @Query(
            "select p from CategoryInterest p join fetch p.identity i where p.batchId=:batch and"
                + " p.categoryName=:category and p.analyticsId>:after order by p.analyticsId")
    List<CategoryInterest> audience(UUID batch, String category, UUID after, Pageable page);

    @Query(
            "select p from CategoryInterest p where p.batchId=:batch and"
                + " p.identity.accountId=:account")
    Optional<CategoryInterest> mine(UUID batch, UUID account);

    @Modifying
    @Query("delete from CategoryInterest p where p.identity.accountId=:account")
    int removeForAccount(UUID account);

    // AI 내부의 공개 이벤트 작업만 참조하며 다른 서비스 테이블과 JOIN하지 않습니다.
    @Modifying
    @Query(
            "delete from CategoryInterest i where i.batchId<>:active and not exists (select"
                + " p.productId from PublishedProduct p where p.completed=false and"
                + " p.batchId=i.batchId)")
    int removeUnusedBatches(UUID active);
}
