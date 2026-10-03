-- 多模型迁移增量：把兼容设置表中更新较晚的账号偏好同步到中性模型偏好表。
-- 部署双写代码前执行；只更新偏好，不读取或修改玩家密钥、命卷和回合。

insert into user_model_preference(
    user_id, provider_code, model_code, credential_source, updated_at
)
select user_id, provider_code, model_code, credential_source, updated_at
from v014_user_model_preference
on duplicate key update
    provider_code = if(values(updated_at) > user_model_preference.updated_at,
        values(provider_code), user_model_preference.provider_code),
    model_code = if(values(updated_at) > user_model_preference.updated_at,
        values(model_code), user_model_preference.model_code),
    credential_source = if(values(updated_at) > user_model_preference.updated_at,
        values(credential_source), user_model_preference.credential_source),
    updated_at = greatest(values(updated_at), user_model_preference.updated_at);
