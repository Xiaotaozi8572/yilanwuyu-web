# manifest.yaml 字段词典

## 字段表

| manifest 字段 | 对应代码字段 | 必填 | 说明 |
|---|---|---|---|
| `source_id` | `SourceRecord.source_id` | 是 | 全局唯一，建议 `c919-<分区>-<短标题>-<版本>` |
| `source_type` | `SourceRecord.source_type` | 是 | `text`/`pdf`/`image`/`table`/`graph` |
| `title` | `SourceRecord.title` | 是 | 人读友好标题 |
| `authority_level` | `SourceRecord.authority_level` | 是 | `official`/`project_reviewed`/`standard` |
| `review_status` | `SourceRecord.review_status` | 是 | `draft`/`candidate`/`reviewed`/`deprecated` |
| `version` | `SourceRecord.version` | 是 | 资料版本，如 `v1`、`v2` |
| `applicable_aircraft` | `SourceRecord.applicable_aircraft` | 是 | 默认 `[c919]` |
| `allowed_use` | `SourceRecord.metadata.allowed_use` | 是 | 见 `allowed_use` 枚举 |
| `aircraft` | `TextChunk.aircraft` | 是 | 入库时写入 chunk 元数据 |
| `component` | `TextChunk.component` | 否 | `02_structure_systems` 必填 |
| `concept` | `TextChunk.concept` | 否 | `03_principles` 必填 |
| `knowledge_type` | `TextChunk.knowledge_type` | 是 | 见 `knowledge_type` 枚举 |
| `content_hash` | `SourceRecord.content_hash` | 否 | 入库时由脚本计算 |
| `original_uri` | `SourceRecord.original_uri` | 否 | 原始下载或引用 URI |
| `timeline` | `metadata.timeline` | 否 | 时间敏感资料的里程碑日期 |
| `text_cross_check` | `VisualAsset.text_cross_check` | 是（视觉类） | 图文交叉验证文本 |

## `allowed_use` 枚举

| 取值 | 用途 | 可用分区 |
|------|------|----------|
| `science_fact_evidence` | 科普事实证据 | `01_science` |
| `teaching_organization` | 教学组织素材 | `02_teaching` |
| `visual_aid` | 视觉辅助 | `03_multimodal/images\|structure_diagrams` |
| `scene_object_binding` | 场景对象绑定 | `03_multimodal/scene_objects` |
| `terminology_graph` | 术语图谱素材 | `04_terms_graph` |
| `reference_only` | 参考资料 | `90_reference_only` |
| `evaluation_seed` | 评测种子 | `_eval_seeds/` |

## `knowledge_type` 枚举

| 取值 | 说明 |
|------|------|
| `overview` | 型号总览 |
| `parameter_fact` | 参数类事实 |
| `component_role` | 部件角色 |
| `system_explanation` | 系统解释 |
| `principle_explanation` | 原理解释 |
| `milestone_event` | 里程碑事件 |
| `value_analysis` | 价值分析 |
| `comparison` | 对比分析 |
| `definition` | 定义说明 |
