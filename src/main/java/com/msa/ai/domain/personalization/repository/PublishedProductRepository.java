package com.msa.ai.domain.personalization.repository;

import com.msa.ai.domain.personalization.entity.PublishedProduct;

import org.springframework.data.jpa.repository.*;

import java.time.Instant;
import java.util.*;

/** 최초 공개 이벤트의 중복과 복제본 간 대상 분할 경합을 DB에서 처리합니다. */
public interface PublishedProductRepository extends JpaRepository<PublishedProduct, UUID> {
    @Modifying
    @Query(
            value =
                    """
insert into ai.published_products(product_id,event_id,payload,published_at,completed)
values (:product,:event,:payload,:published,false) on conflict (product_id) do nothing
""",
            nativeQuery = true)
    int insertOnce(UUID product, UUID event, String payload, Instant published);

    @Query(
            value =
                    "select * from ai.published_products where completed=false order by"
                        + " published_at,product_id limit 1 for update skip locked",
            nativeQuery = true)
    Optional<PublishedProduct> next();
}
