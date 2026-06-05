package com.astra.observability.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "clickhouse")
public class ClickHouseProperties {
    private String url = "http://clickhouse:8123";
    private String database = "astra";
    private String table = "gateway_metrics";
    private String user = "default";
    private String password = "";
    private int flushIntervalMs = 5000;
    private int batchSize = 500;
}
