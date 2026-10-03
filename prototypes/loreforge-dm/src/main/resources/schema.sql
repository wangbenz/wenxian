CREATE TABLE demo_meta (schema_version INT NOT NULL);
INSERT INTO demo_meta VALUES (1);
CREATE TABLE demo_saves (
  id VARCHAR(36) PRIMARY KEY,
  token_hash VARCHAR(64) NOT NULL,
  revision INT NOT NULL,
  snapshot CLOB NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE demo_saves IS '独立试玩存档；当前事实只从已提交快照读取';
CREATE TABLE demo_turns (
  save_id VARCHAR(36) NOT NULL REFERENCES demo_saves(id),
  request_id VARCHAR(36) NOT NULL,
  fingerprint VARCHAR(64) NOT NULL,
  request_json CLOB NOT NULL,
  status VARCHAR(16) NOT NULL,
  response_json CLOB,
  diagnostic_json CLOB,
  failure_message VARCHAR(300),
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY(save_id, request_id)
);
COMMENT ON TABLE demo_turns IS '幂等请求账本；生成失败不改变存档，成功响应与存档同事务提交';
CREATE TABLE demo_model_calls (
  id VARCHAR(36) PRIMARY KEY,
  request_id VARCHAR(36) NOT NULL,
  request_trace CLOB NOT NULL,
  response_trace CLOB,
  input_tokens INT,
  output_tokens INT,
  duration_ms BIGINT,
  status VARCHAR(16) NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE demo_model_calls IS '独立测试调用预算与原始证据；不保存密钥和请求头';
