-- 仅记录响应形态、工具名称白名单及数量，不保存正文、参数、思考文本或原始响应。
ALTER TABLE model_call ADD COLUMN response_shape_json TEXT NOT NULL DEFAULT '{}';
