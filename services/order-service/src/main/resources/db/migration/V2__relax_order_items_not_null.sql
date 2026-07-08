-- The order is persisted BEFORE the saga's VALIDATE_ITEMS step fetches the
-- server-side price from restaurant-service. Until that step runs, name and
-- unit_price are unknown. Make both columns nullable so the early persist
-- doesn't fail with a NOT NULL violation. The saga fills them in within a
-- few hundred ms; readers that fetch the order before then will see nulls.
ALTER TABLE order_items ALTER COLUMN name DROP NOT NULL;
ALTER TABLE order_items ALTER COLUMN unit_price DROP NOT NULL;
