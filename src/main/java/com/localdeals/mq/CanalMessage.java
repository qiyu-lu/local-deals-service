package com.localdeals.mq;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * POJO mapping for Canal's FlatMessage JSON format, published to RocketMQ topic
 * {@code mysql-sync-topic} whenever a watched table's binlog changes.
 *
 * <p>Example payload:
 * <pre>{@code
 * {
 *   "database": "local_deals",
 *   "table": "tb_shop",
 *   "type": "UPDATE",
 *   "data": [{"id": "1", "name": "新白鹿", "x": "120.15", "y": "30.33", "type_id": "1"}],
 *   "old": [{"name": "旧名字"}],
 *   "isDdl": false
 * }
 * }</pre>
 */
@Data
public class CanalMessage {
    private String database;
    private String table;
    private String type;  // "INSERT", "UPDATE", "DELETE", "DDL"
    private List<Map<String, Object>> data;
    private List<Map<String, Object>> old;
    private Boolean isDdl;
}
