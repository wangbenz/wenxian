-- 只读聚合；先核对 story_save 字段，不输出账号、命卷 ID、正文或其他玩家隐私。
-- 本轮仅提供脚本，未在共享数据库执行。不得把结果直接当确定性迁移许可。
select world_code,status,state_schema_version,count(*) as save_count,
       sum(case when json_valid(state_json)=1 then 0 else 1 end) as invalid_json_count
from story_save group by world_code,status,state_schema_version;

select world_code,state_schema_version,
       json_unquote(json_extract(safe_state,'$.schemaVersion')) as json_schema_version,
       json_unquote(json_extract(safe_state,'$.creationContract.worldBinding.packageId')) as package_id,
       json_unquote(json_extract(safe_state,'$.creationContract.worldBinding.revision')) as package_revision,
       json_unquote(json_extract(safe_state,'$.creationContract.realmBinding.scaleId')) as realm_scale_id,
       json_unquote(json_extract(safe_state,'$.creationContract.realmBinding.scaleRevision')) as realm_scale_revision,
       count(*) as save_count
from (
    select world_code,state_schema_version,
           case when json_valid(state_json)=1 then state_json else '{}' end as safe_state
    from story_save
) inventory
group by world_code,state_schema_version,json_schema_version,package_id,package_revision,realm_scale_id,realm_scale_revision;

-- 后续迁移必须另做：包ID/修订/namespace精确映射；raceId、stageId、phase及完整cultivationSystem逐项核对；
-- 主表与JSON版本一致性；损坏与无绑定分类；源/目标快照哈希和可恢复点。不能依据上述汇总自动回填。
