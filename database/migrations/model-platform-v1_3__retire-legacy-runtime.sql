-- 统一模型运行时切换前的数据接续。
-- 本脚本只把尚未进入中性模型表的历史密钥与偏好复制过去，不删除历史表，也不修改命卷事实。

-- 平台密钥已有中性值时绝不覆盖；没有数据库密钥时仍可由部署环境在应用启动时加密补齐。
update ai_provider_config current_provider
join ai_model_config legacy_model
  on legacy_model.provider_code=current_provider.provider_code
 and legacy_model.active=true
 and legacy_model.secret_ciphertext is not null
set current_provider.platform_secret_ciphertext=legacy_model.secret_ciphertext,
    current_provider.updated_at=greatest(current_provider.updated_at, legacy_model.updated_at)
where current_provider.platform_secret_ciphertext is null
   or char_length(trim(current_provider.platform_secret_ciphertext))=0;

-- 玩家供应商级密钥是唯一运行时事实；已经存在的新记录优先。
insert ignore into user_ai_provider_credential(
    user_id, provider_code, api_key_ciphertext, masked_suffix, status,
    last_verified_at, last_error_code, created_at, updated_at
)
select user_id, provider, api_key_ciphertext, masked_suffix, status,
       last_verified_at, last_error_code, created_at, updated_at
from user_ai_credential;

-- 仅当历史偏好更新时间更晚时接续，避免覆盖玩家已在统一设置页完成的新选择。
insert into user_model_preference(
    user_id, provider_code, model_code, credential_source, updated_at
)
select user_id, provider_code, model_code, credential_source, updated_at
from v014_user_model_preference
on duplicate key update
    provider_code=if(values(updated_at)>user_model_preference.updated_at,
        values(provider_code), user_model_preference.provider_code),
    model_code=if(values(updated_at)>user_model_preference.updated_at,
        values(model_code), user_model_preference.model_code),
    credential_source=if(values(updated_at)>user_model_preference.updated_at,
        values(credential_source), user_model_preference.credential_source),
    updated_at=greatest(values(updated_at), user_model_preference.updated_at);
