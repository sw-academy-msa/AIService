-- 상품 상세 조회만 사용하며 새로고침은 사용자·상품·GA 날짜별 마지막 한 건으로 합칩니다.
WITH daily_views AS (
    SELECT user_id AS analytics_id, item.item_category AS category_name,
           TIMESTAMP_MICROS(event_timestamp) AS viewed_at
    FROM `__EVENTS_TABLE__`, UNNEST(items) AS item
    WHERE _TABLE_SUFFIX BETWEEN @from_date AND @through_date
      AND REGEXP_CONTAINS(_TABLE_SUFFIX, r'^[0-9]{8}$')
      AND event_name = 'view_item'
      AND privacy_info.analytics_storage = 'Yes'
      AND user_id IS NOT NULL AND user_id != ''
      AND item.item_id IS NOT NULL AND item.item_id != ''
      AND item.item_category IS NOT NULL AND item.item_category != ''
    QUALIFY ROW_NUMBER() OVER (
        PARTITION BY user_id, item.item_id, event_date
        ORDER BY event_timestamp DESC, item.item_category ASC
    ) = 1
)
SELECT analytics_id, category_name, COUNT(*) AS views,
       UNIX_MICROS(MAX(viewed_at)) AS last_view_micros
FROM daily_views
GROUP BY analytics_id, category_name
ORDER BY analytics_id, category_name
LIMIT @row_limit
