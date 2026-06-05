-- ClickHouse Schema for Astra Gateway Observability
-- Run once on the ClickHouse instance before starting the observability-service.

-- ============================================================================
-- GATEWAY METRICS  (one row per AI request)
-- ============================================================================

CREATE TABLE IF NOT EXISTS astra.gateway_metrics
(
    timestamp     DateTime     DEFAULT now(),
    request_id    String,
    provider      LowCardinality(String),
    model         LowCardinality(String),
    latency_ms    UInt32,
    input_tokens  UInt32,
    output_tokens UInt32,
    total_tokens  UInt32,
    cost_usd      Float64,
    status        LowCardinality(String),   -- success | failed | timeout
    tenant_id     String       DEFAULT ''
)
ENGINE = MergeTree()
PARTITION BY toYYYYMM(timestamp)
ORDER BY (timestamp, provider, model)
TTL timestamp + INTERVAL 90 DAY
SETTINGS index_granularity = 8192;

-- ============================================================================
-- HOURLY ROLLUP (materialised aggregation — refreshed by ClickHouse)
-- ============================================================================

CREATE MATERIALIZED VIEW IF NOT EXISTS astra.gateway_metrics_hourly
ENGINE = SummingMergeTree()
PARTITION BY toYYYYMM(hour)
ORDER BY (hour, provider, model, status)
AS
SELECT
    toStartOfHour(timestamp)       AS hour,
    provider,
    model,
    status,
    count()                        AS request_count,
    sum(input_tokens)              AS input_tokens,
    sum(output_tokens)             AS output_tokens,
    sum(total_tokens)              AS total_tokens,
    sum(cost_usd)                  AS total_cost_usd,
    avg(latency_ms)                AS avg_latency_ms,
    quantile(0.95)(latency_ms)     AS p95_latency_ms,
    quantile(0.99)(latency_ms)     AS p99_latency_ms
FROM astra.gateway_metrics
GROUP BY hour, provider, model, status;

-- ============================================================================
-- DAILY ROLLUP
-- ============================================================================

CREATE MATERIALIZED VIEW IF NOT EXISTS astra.gateway_metrics_daily
ENGINE = SummingMergeTree()
PARTITION BY toYYYYMM(day)
ORDER BY (day, provider, model)
AS
SELECT
    toDate(timestamp)              AS day,
    provider,
    model,
    count()                        AS request_count,
    sum(total_tokens)              AS total_tokens,
    sum(cost_usd)                  AS total_cost_usd,
    avg(latency_ms)                AS avg_latency_ms,
    countIf(status = 'success')    AS success_count,
    countIf(status = 'failed')     AS failed_count
FROM astra.gateway_metrics
GROUP BY day, provider, model;
