# Task 2 Review Package v3

Current contents after documentation audit fix.

## docs/spec.md

```
# 缈艰鏃犱綑 AI 鏅鸿兘瀵煎笀绯荤粺寮€鍙戞墽琛岃鏍?
## 鏂囨。渚濇嵁

鏈枃涓?`task.md` 浣跨敤鍚屼竴濂?P0 鍒?P8 闃舵缂栧彿锛岃鏄庢瘡涓樁娈靛浣曞伐绋嬪寲瀹炵幇銆傛墍鏈夊疄鐜板姩浣滃潎杩芥函鍒般€婄考瑙堟棤浣欐櫤鑳戒綋妯″潡璁捐鏂囨。V2_澧炲姞璇煶浜や簰.docx銆嬩腑鐨勬ā鍧楄竟鐣屻€佺粺涓€鐘舵€佹満銆佹暟鎹绾︺€丷AG 璇佹嵁鏍稿績銆佽蹇嗘不鐞嗗拰璇煶浜や簰琛ュ厖銆?
## 闇€瑕佽皟鐢ㄧ殑 skill

鍚庣画璁?Codex 鎵ц鏈枃鏃讹紝涓嶈姹傛瘡涓樁娈甸鍏堝浐瀹?skill锛岃€屾槸鍏堣皟鐢?`using-superpowers`锛岀敱瀹冩牴鎹綋鍓嶉樁娈典换鍔°€佸疄鐜拌瑷€銆佹枃浠剁被鍨嬨€侀敊璇姸鎬佸拰鍙敤宸ュ叿鍔ㄦ€佸垽鏂繕闇€瑕佸摢浜?skill銆備负婊¤冻宸ヤ綔鍖?AGENTS.md 涓€淐odex 鎻愮ず璇嶅繀椤绘槑纭０鏄?skill鈥濈殑瑕佹眰锛屾湰鎻愮ず璇嶅彧澹版槑鍏ㄥ眬蹇呴渶 skill锛?
1. `using-superpowers`锛氭瘡娆″紑濮嬮樁娈靛疄鐜板墠鍏堣皟鐢紝鐢ㄤ簬鍙戠幇骞惰皟搴﹀綋鍓嶉樁娈电湡姝ｉ渶瑕佺殑 skill銆?2. `writing-plans`锛氬綋闃舵娑夊強澶氭枃浠躲€佸妯″潡鎴栧姝ラ瀹炵幇鏃惰皟鐢紝鐢ㄤ簬鐢熸垚鏂囦欢绾у疄鏂借鍒掋€?3. `systematic-debugging`锛氶亣鍒?bug銆佹祴璇曞け璐ャ€佹绱?鐢熸垚/鑷/璇煶鐘舵€佹満寮傚父鏃惰皟鐢ㄣ€?4. `verification-before-completion`锛氶樁娈靛畬鎴愬墠蹇呴』璋冪敤锛岀敤浜庤繍琛屾祴璇曘€乻chema 鏍￠獙鍜屾枃妗ｄ竴鑷存€ф鏌ャ€?5. `openai-docs`锛氫粎褰撻樁娈垫秹鍙?OpenAI API銆丷ealtime銆丼peech to Text銆乀ext to Speech銆丼tructured Outputs 鎴栨ā鍨嬮厤缃椂璋冪敤锛岀敤浜庢煡璇㈠畼鏂规渶鏂版枃妗ｃ€?
闄や笂杩板叏灞€瑙勫垯澶栵紝鏈枃涓嶅湪姣忎釜闃舵鍗曠嫭鎸囧畾 skill銆傞樁娈垫墽琛岃€呭繀椤诲湪杩涘叆鍏蜂綋瀹炵幇鍓嶄緷鎹?`using-superpowers` 鐨勫垽鏂姩鎬佽ˉ鍏呰皟鐢ㄧ浉鍏?skill锛屼緥濡傚悜閲忔绱€佽闊宠浆鍐欍€丩angGraph 缂栨帓鎴栨暟鎹鐞嗙被 skill銆?
## 鎺ㄨ崘宸ョ▼鐩綍缁撴瀯

```text
project-root/
  README.md
  pyproject.toml
  .env.example
  configs/
    app.yaml
    providers.yaml
    prompts.yaml
    memory.yaml
    rag.yaml
    voice.yaml
    evals.yaml
  src/
    app/
      main.py
      api/
        http_routes.py
        ws_routes.py
        schemas.py
    core/
      actions.py
      contracts.py
      errors.py
      state_machine.py
      tracing.py
      settings.py
    input/
      query_object.py
      query_understanding.py
      scene_binding.py
      voice_query_normalizer.py
    prompts/
      asset_models.py
      repository.py
      router.py
      assembler.py
      evaluation.py
    memory/
      schemas.py
      event_log.py
      controller.py
      stores.py
      temporal.py
      maintenance_jobs.py
      audit.py
    knowledge/
      schemas.py
      source_registry.py
      scene_object_registry.py
      ingestion/
        text_ingestor.py
        pdf_ingestor.py
        visual_ingestor.py
        scene_ingestor.py
      indexes/
        keyword_index.py
        vector_index.py
        hybrid_index.py
        visual_page_index.py
        graph_index.py
      retrieval_controller.py
      evidence_package.py
      evidence_gate.py
    generation/
      answer_types.py
      evidence_sketch.py
      planner.py
      generator.py
      citation_binding.py
      display_blocks.py
    self_check/
      claim_extractor.py
      evidence_alignment.py
      scene_checker.py
      multimodal_checker.py
      boundary_checker.py
      score_card.py
      decision_router.py
    feedback/
      feedback_event.py
      parser.py
      checkpoint_store.py
      evidence_lock.py
      rewrite_planner.py
      rewriter.py
      delta_map.py
    voice/
      transport.py
      session_state.py
      vad.py
      asr.py
      terminology.py
      tts.py
      barge_in.py
      metrics.py
    services/
      llm_provider.py
      embedding_provider.py
      storage.py
      clock.py
    utils/
      ids.py
      json_schema.py
      text_normalize.py
  tests/
    unit/
      core/
      prompts/
      memory/
      knowledge/
      generation/
      self_check/
      feedback/
      voice/
    integration/
      rag_pipeline/
      answer_pipeline/
      feedback_loop/
      voice_loop/
    e2e/
      fixtures/
      scenarios/
  data/
    raw_sources/
      .gitkeep
    processed/
      .gitkeep
    demo/
      .gitkeep
  scripts/
    ingest_sources.py
    seed_demo_data.py
    run_eval.py
    export_trace_report.py
  docs/
    architecture.md
    api_contracts.md
    eval_plan.md
```

## 鍏ㄥ眬鎵ц鍘熷垯

- 浜嬪疄鍙潵鑷?`evidence_package`锛岃蹇嗗彧褰卞搷涓€у寲琛ㄨ揪鍜屾寚浠ｈВ鏋愶紝Prompt 鍙奖鍝嶈娉曞拰杈撳嚭鏍煎紡銆?- 鎵€鏈夋ā鍧楅€氳繃 schema 瀵硅薄浼犻€掓暟鎹紝涓嶄紶閫掓湭缁撴瀯鍖栧ぇ瀛楀吀銆?- 鎵€鏈夐樁娈靛繀椤昏褰?`run_trace`锛岃嚦灏戜繚鐣欓樁娈佃緭鍏ユ憳瑕併€佽緭鍑哄璞?id銆佸喅绛栥€侀敊璇拰鑰楁椂銆?- 鎵€鏈夊彲鍙?Provider 閮介€氳繃鎺ュ彛鍜岄厤缃敞鍏ワ紝閬垮厤鍦ㄤ笟鍔℃ā鍧椾腑纭紪鐮佹ā鍨嬨€佹暟鎹簱鎴栨湇鍔″晢銆?- `寰呯‘璁 瀛楁涓嶅緱琚唬鐮佺敤榛樿浜嬪疄鍋峰伔濉弧锛屽彧鑳介€氳繃閰嶇疆銆佹祴璇曞す鍏锋垨鏄惧紡闄嶇骇澶勭悊銆?
## P1 Prompt Refactor Update

鏈妭鐢ㄤ簬瑕嗙洊鏃х増 P1 Prompt 鏂囨。涓笌褰撳墠瀹炵幇鍐茬獊鐨勬弿杩帮紝鍙鏄?Prompt 瀹¤鐩稿叧鐨勭幇琛屽绾︺€?
- `src/prompts/repository.py` 宸叉浛浠ｅ垹闄ょ殑 `src/prompts/asset_store.py`銆?- Prompt 璧勪骇鐩綍鍥哄畾涓?`assets/prompts/<template_id>/asset.json`銆乣assets/prompts/<template_id>/versions/*.json`銆乣assets/prompts/<template_id>/evaluations/*.json`銆?- `PromptAssetRepository` 鏄敮涓€鐨勮祫浜с€佺増鏈拰璇勪及蹇収鍔犺浇鍣紱杩愯鎬?`active` / `experimental` 鐗堟湰蹇呴』瀛樺湪宸叉壒鍑嗗揩鐓э紝涓旂増鏈?`snapshot_id` 蹇呴』涓庡搴旇瘎浼板揩鐓?`snapshot_id` 瀹屽叏涓€鑷达紝鍚﹀垯鎷掔粷鍔犺浇銆?- `PromptAssembler` 杩斿洖 canonical `PromptMessageBundle`锛屼笉瀛樺湪鐙珛鐨?`MessageBundle` 杩愯鏃跺绾︺€?- `PromptMessageBundle.messages` 鍙兘鍖呭惈 `services.model_client.ModelMessage`锛岃鑹插彧鍏佽 `system` 鍜?`user`銆?- 甯歌 trace 鍙褰?template id銆乿ersion銆乻napshot id銆乵issing variables銆乺oute reason 鍜?injection summary锛屼笉璁板綍瀹屾暣 prompt 姝ｆ枃銆?- Prompt 璧勪骇鍙畾涔夎鏄庨鏍笺€佹暀瀛﹂鏍煎拰杈撳嚭琛屼负锛屼笉鎻愪緵鑸┖浜嬪疄锛涗簨瀹炰粛蹇呴』鏉ヨ嚜 `evidence_package`銆?- 鏃у钩閾?YAML Prompt 璧勪骇銆乣PromptAssetStore`銆乣default_prompt_assets`銆佷互鍙?`role="context"` / `role: context` 娑堟伅瑙掕壊閮芥槸绂佹椤广€?
## P0锛氬伐绋嬮鏋躲€佺粺涓€鐘舵€佹満涓庢暟鎹绾?
### 瀵瑰簲 task.md 涓殑闃舵鐩爣

寤虹珛鍙墿灞曢」鐩鏋躲€佸叏灞€鐘舵€佹満銆佺粺涓€鏁版嵁瀵硅薄銆佸姩浣滄灇涓俱€侀厤缃綋绯诲拰鏃ュ織杩借釜锛屼娇鍚庣画妯″潡涓嶄細鍚勮嚜鍙戞槑鎺ュ彛銆?
### 鎺ㄨ崘宸ョ▼鐩綍缁撴瀯

閲嶇偣鍒涘缓 `src/core/`銆乣configs/`銆乣tests/unit/core/` 鍜岄《灞傚伐绋嬫枃浠躲€?
### 闇€瑕佹柊澧炴垨淇敼鐨勬枃浠?
- `pyproject.toml`
- `.env.example`
- `configs/app.yaml`
- `configs/providers.yaml`
- `src/core/actions.py`
- `src/core/contracts.py`
- `src/core/state_machine.py`
- `src/core/errors.py`
- `src/core/tracing.py`
- `src/core/settings.py`
- `tests/unit/core/test_contracts.py`
- `tests/unit/core/test_state_machine.py`

### 鏍稿績绫汇€佸嚱鏁般€佹ā鍧楁垨鏈嶅姟璁捐

- `ActionDecision`锛氱粺涓€鍔ㄤ綔鏋氫妇銆?- `AgentState`锛氬叏灞€鐘舵€佹灇涓俱€?- `StateTransition`锛氳褰?from_state銆乼o_state銆乤ction銆乺eason銆?- `RunTrace`锛氱粺涓€杩愯杩借釜瀵硅薄銆?- `BaseContract`锛氭墍鏈?schema 鐨勫熀纭€鏍￠獙绫汇€?- `Settings`锛氬姞杞?YAML銆佺幆澧冨彉閲忓拰榛樿鍊笺€?
### 鏁版嵁娴?/ 璋冪敤閾捐矾

1. API 鎴栨祴璇曞叆鍙ｅ垱寤?`run_id`銆?2. 杈撳叆琚寘瑁呬负鍩虹 `RunTrace`銆?3. 鐘舵€佹満浠?`INPUT_RECEIVED` 寮€濮嬨€?4. 鍚庣画闃舵鍙厑璁搁€氳繃 `transition(next_state, reason)` 鏀瑰彉鐘舵€併€?
### 鎺ュ彛璁捐

```text
transition(run_trace, next_state, action_decision=None, reason=None) -> RunTrace
validate_contract(payload, contract_type) -> Contract
load_settings(config_path, env) -> Settings
```

### 閰嶇疆椤硅璁?
- `app.environment`
- `app.log_level`
- `app.max_state_loop`
- `providers.llm.default`
- `providers.embedding.default`
- `trace.persist_enabled`

### 閿欒澶勭悊鏂瑰紡

- schema 鏍￠獙澶辫触鎶涘嚭 `ContractValidationError`銆?- 闈炴硶鐘舵€佽烦杞姏鍑?`InvalidStateTransitionError`銆?- 閰嶇疆缂哄け鎶涘嚭 `ConfigError`锛岄敊璇腑蹇呴』鍖呭惈缂哄け閿悕銆?
### 鏃ュ織涓庣洃鎺ц姹?
- 姣忔鐘舵€佽烦杞褰?run_id銆乫rom_state銆乼o_state銆乤ction_decision銆乺eason銆乴atency_ms銆?- 鏃ュ織涓嶅緱杈撳嚭鏁忔劅鍘熷璇煶鎴栫敤鎴烽殣绉佸瓧娈点€?
### 娴嬭瘯鏂规

- 鍗曟祴瑕嗙洊鎵€鏈夊姩浣滄灇涓惧拰鐘舵€佹灇涓俱€?- 鍗曟祴楠岃瘉闈炴硶璺宠浆浼氬け璐ャ€?- 鍗曟祴楠岃瘉 `RunTrace` 鏈€灏忓瓧娈靛畬鏁淬€?
### 闃舵楠屾敹鏂瑰紡

杩愯 core 鍗曟祴锛岀‘璁?P0 schema銆佺姸鎬佹満銆侀厤缃姞杞藉叏閮ㄩ€氳繃銆?
### 寰呯‘璁?/ 璁捐鍋囪 / 娼滃湪椋庨櫓

- 寰呯‘璁わ細鏄惁浣跨敤 Pydantic銆乨ataclass 鎴栧叾浠?schema 妗嗘灦銆?- 璁捐鍋囪锛氬厛鐢?Python schema 瀹炵幇锛屽悗缁彲瀵煎嚭 JSON Schema 缁欏墠绔€?- 娼滃湪椋庨櫓锛氬悗缁樁娈电粫杩?`core.contracts` 鐩存帴浼?dict銆?
## P1锛氳緭鍏ョ悊瑙ｃ€佸満鏅姸鎬佷笌 Prompt 璺敱

### 瀵瑰簲 task.md 涓殑闃舵鐩爣

灏嗘枃鏈緭鍏ュ拰 3D 鍦烘櫙鐘舵€佹爣鍑嗗寲涓?`query_object`锛屽苟瀹屾垚 Prompt 璧勪骇璺敱銆佸彉閲忓～鍏呭拰娉ㄥ叆杈圭晫銆?
### 鎺ㄨ崘宸ョ▼鐩綍缁撴瀯

閲嶇偣鍒涘缓 `src/input/` 鍜?`src/prompts/`锛岃ˉ鍏?`configs/prompts.yaml`銆?
### 闇€瑕佹柊澧炴垨淇敼鐨勬枃浠?
- `src/input/query_object.py`
- `src/input/query_understanding.py`
- `src/input/scene_binding.py`
- `src/prompts/asset_models.py`
- `src/prompts/repository.py`
- `src/prompts/router.py`
- `src/prompts/assembler.py`
- `configs/prompts.yaml`
- `tests/unit/prompts/test_prompt_router.py`
- `tests/unit/prompts/test_prompt_assembler.py`
- `tests/unit/core/test_contracts.py`

### 鏍稿績绫汇€佸嚱鏁般€佹ā鍧楁垨鏈嶅姟璁捐

- `QueryObject`锛氫繚瀛?raw_query銆乶ormalized_query銆乮ntent_type銆乼arget_aircraft銆乼arget_component銆乻cene_object_id銆乶eeds_clarification銆?- `SceneBinder`锛氭牴鎹?scene_state 鍜屾寚浠ｈ瘝缁戝畾鍦烘櫙瀵硅薄銆?- `TeachingPromptAsset`锛歅rompt 璧勪骇鎬绘。妗堛€?- `PromptContentVersion`锛歅rompt 鏂囨湰鐗堟湰銆?- `PromptRouter`锛氭牴鎹换鍔°€佸満鏅€佽蹇嗗拰璇佹嵁閫夋嫨 active Prompt銆?- `PromptAssembler`锛氭寜鍥哄畾娉ㄥ叆椤哄簭鏋勫缓 messages銆?
### 鏁版嵁娴?/ 璋冪敤閾捐矾

1. raw user input 涓?scene_state 杩涘叆 `QueryUnderstandingService`銆?2. `SceneBinder` 灏濊瘯缁戝畾椋炴満銆侀儴浠躲€佺儹鐐瑰璞°€?3. 鐢熸垚 `QueryObject`銆?4. `PromptRouter` 鏍规嵁 intent_type 鍜?scene_scope 鏌ユ壘 active Prompt銆?5. `PromptAssembler` 绛夊緟 P2/P3 杈撳嚭鍚庡畬鎴愭渶缁堟敞鍏ャ€?
### 鎺ュ彛璁捐

```text
understand_query(raw_query, scene_state, dialogue_context) -> QueryObject
bind_scene_reference(query_object, scene_state) -> SceneBindingResult
select_prompt(query_object, scene_state, memory_context, evidence_package) -> PromptSelection
assemble_messages(prompt_selection, evidence_package, memory_context, output_contract) -> PromptMessageBundle
```

### 閰嶇疆椤硅璁?
- `prompts.default_template_id`
- `prompts.allowed_runtime_statuses: [active, experimental]`
- `prompts.injection_order`
- `prompts.required_variables`
- `prompts.fallback_template_id`

### 閿欒澶勭悊鏂瑰紡

- 缂哄皯蹇呭～ Prompt 鍙橀噺鏃惰繑鍥為檷绾у師鍥狅紝涓嶆姏涓氬姟鑷村懡閿欒銆?- 鍦烘櫙鎸囦唬澶氬€欓€夋椂璁剧疆 `needs_clarification=true`銆?- 闈?active Prompt 琚矾鐢遍€変腑鏃舵姏鍑?`PromptStatusError`銆?
### 鏃ュ織涓庣洃鎺ц姹?
- 璁板綍 selected_template_id銆乵issing_variables銆乫allback_reason銆乻cene_binding_confidence銆?- 涓嶈褰曞畬鏁?Prompt 瀵嗘枃鎴栨湭鑴辨晱涓汉璁板繂銆?
### 娴嬭瘯鏂规

- 鍦烘櫙鎸囦唬娴嬭瘯锛氣€滆繖涓儴浠舵湁浠€涔堢敤鈥濆湪鏈?selected_object_id 鏃舵纭粦瀹氥€?- 缂哄け鍙橀噺娴嬭瘯锛氱己灏?`rag_evidence` 鏃朵笉鐢熸垚浜嬪疄鍨嬫渶缁?Prompt銆?- 鐘舵€佹祴璇曪細draft/candidate/deprecated 涓嶈兘琚寮忚矾鐢便€?
### 闃舵楠屾敹鏂瑰紡

鐢ㄦā鎷?scene_state 鍜?query 杈撳叆锛岀‘璁ゅ彲浠ョ敓鎴愮ǔ瀹?`QueryObject`銆丳rompt 閫夋嫨缁撴灉鍜屾敞鍏ユ憳瑕併€?
### 寰呯‘璁?/ 璁捐鍋囪 / 娼滃湪椋庨櫓

- 寰呯‘璁わ細棣栨壒 Prompt 妯℃澘鍐呭鍜屼汉宸ュ鏍告祦绋嬨€?- 璁捐鍋囪锛歅rompt 璧勪骇鍏堢敤鏈湴 JSON 鐩綍瀛樺偍锛岀洰褰曠粨鏋勯伒寰?`asset.json + versions/*.json + evaluations/*.json`銆?- 娼滃湪椋庨櫓锛氳矾鐢卞櫒涓轰簡琛ラ綈鍙橀噺鐚滄祴鑸┖浜嬪疄銆?
## P2锛氳蹇嗙郴缁熶笌鏃堕棿鏈夋晥鎬ф不鐞?
### 瀵瑰簲 task.md 涓殑闃舵鐩爣

瀹炵幇浜嬩欢鍏堣銆佸€欓€夋娊鍙栥€佹椂闂存湁鏁堛€佹潈闄愭不鐞嗐€佸璁″彲杩芥函鐨勮蹇嗙郴缁燂紝淇濊瘉璁板繂鍙敤浜庝釜鎬у寲鑰屼笉鍏呭綋鑸┖浜嬪疄銆?
### 鎺ㄨ崘宸ョ▼鐩綍缁撴瀯

閲嶇偣鍒涘缓 `src/memory/`銆乣configs/memory.yaml` 鍜?`tests/unit/memory/`銆?
### 闇€瑕佹柊澧炴垨淇敼鐨勬枃浠?
- `src/memory/schemas.py`
- `src/memory/event_log.py`
- `src/memory/controller.py`
- `src/memory/stores.py`
- `src/memory/temporal.py`
- `src/memory/maintenance_jobs.py`
- `src/memory/audit.py`
- `configs/memory.yaml`
- `tests/unit/memory/test_memory_write_flow.py`
- `tests/unit/memory/test_temporal_status.py`
- `tests/unit/memory/test_memory_context.py`

### 鏍稿績绫汇€佸嚱鏁般€佹ā鍧楁垨鏈嶅姟璁捐

- `InteractionEvent`锛氱粺涓€浜嬩欢鍏ュ彛銆?- `MemoryCandidate`锛氬€欓€夎蹇嗐€?- `StructuredMemory`锛氬涔犺€呯敾鍍忋€佹帉鎻″害銆佽瑙ｃ€佹巿鏉冦€?- `MemoryController`锛氳鍐欐潈闄愩€佹娊鍙栥€佹牎楠屻€佹绱㈠拰娉ㄥ叆鎺у埗銆?- `TemporalNormalizer`锛氳В鏋?observed_at銆乿alid_from銆乪xpires_at銆?- `ConflictDetector`锛氭娴嬪亸濂芥垨璇В鍐茬獊銆?- `MemoryContextBuilder`锛氱敓鎴?`memory_context`銆?
### 鏁版嵁娴?/ 璋冪敤閾捐矾

1. P1/P4/P6/P7 浜х敓浜嬩欢锛屽啓鍏?`event_log`銆?2. `MemoryExtractor` 鎶藉彇鍊欓€夎蹇嗐€?3. `TemporalNormalizer` 琛ユ椂闂村瓧娈点€?4. `ConflictDetector` 鍒ゆ柇鏇夸唬銆佸啿绐佹垨閲嶅銆?5. `GovernanceChecker` 妫€鏌ラ殣绉併€佸畨鍏ㄥ拰鏉冮檺銆?6. `MemoryStore` 鍐欏叆缁撴瀯鍖栧瓨鍌ㄣ€佸悜閲忚涔夊眰鎴栨椂闂村浘璋便€?7. 鍥炵瓟鍓嶇敱 `MemoryController` 鐢熸垚 `memory_context`銆?
### 鎺ュ彛璁捐

```text
record_event(event: InteractionEvent) -> EventId
extract_memory_candidates(event_id) -> list[MemoryCandidate]
upsert_memory(candidate, policy_memory) -> MemoryWriteResult
build_memory_context(query_object, scene_state, budget) -> MemoryContext
run_memory_maintenance(job_type, now) -> MaintenanceReport
```

### 閰嶇疆椤硅璁?
- `memory.max_context_items`
- `memory.default_expiration_days`
- `memory.low_confidence_threshold`
- `memory.privacy_levels`
- `memory.decay_policy`
- `memory.allowed_long_term_types`

### 閿欒澶勭悊鏂瑰紡

- 鏈巿鏉冨啓鍏ヨ繑鍥?`MemoryRejected`锛屽苟璁板綍瀹¤鍘熷洜銆?- 鍐茬獊璁板繂涓嶈鐩栧垹闄ゆ棫璁板綍锛岃€屾槸寤虹珛 supersedes/superseded_by銆?- 浣庣疆淇?ASR 浜嬩欢鍙兘杩涘叆 event_log锛屼笉杩涘叆闀挎湡璁板繂銆?
### 鏃ュ織涓庣洃鎺ц姹?
- 璁板綍 memory_id銆乻ource_event_id銆亀rite_agent銆乧onfidence銆乻tatus銆乸rivacy_level銆乨ecision_reason銆?- 鐩戞帶 memory_pollution_rate銆乪xpired_memory_hit_count銆乧onflict_count銆?
### 娴嬭瘯鏂规

- 鍐欏叆娴佺▼娴嬭瘯锛氫簨浠跺埌鍊欓€夊啀鍒?active銆?- 鍐茬獊娴嬭瘯锛氭棫鍋忓ソ琚柊鍋忓ソ superseded銆?- 杈圭晫娴嬭瘯锛氱敤鎴疯鐨勮埅绌轰簨瀹炰笉鑳借繘鍏ョ煡璇嗗簱浜嬪疄琛ㄣ€?- 鏃堕棿娴嬭瘯锛歟xpires_at 鍚庝笉杩涘叆 `memory_context`銆?
### 闃舵楠屾敹鏂瑰紡

鐢ㄤ笁杞ā鎷熷璇濋獙璇佺郴缁熻兘璁颁綇琛ㄨ揪鍋忓ソ銆佸綋鍓嶄富棰樺拰璇В鍊欓€夛紝骞惰兘鍦ㄨ繃鏈熸垨鍐茬獊鏃堕檷鏉冦€?
### 寰呯‘璁?/ 璁捐鍋囪 / 娼滃湪椋庨櫓

- 寰呯‘璁わ細鐪熷疄鐢ㄦ埛鎺堟潈 UI 鍜屽垹闄ゆ祦绋嬨€?- 璁捐鍋囪锛氫簨浠舵棩蹇楀拰缁撴瀯鍖栬蹇嗗厛鐢?SQLite 鎴栬交閲忓瓨鍌ㄦ娊璞°€?- 娼滃湪椋庨櫓锛氫簨浠跺師鏂囧惈闅愮锛屾棩蹇楀鍑烘椂鏈劚鏁忋€?
## P3锛氭湰鍦拌埅绌虹煡璇嗗簱銆丷AG 涓庡妯℃€佽瘉鎹寘

### 瀵瑰簲 task.md 涓殑闃舵鐩爣

鏋勫缓鑸┖浜嬪疄璇佹嵁鏍稿績锛屾敮鎸佹枃鏈€丳DF銆佸浘鐗囥€佺粨鏋勫浘銆?D 鍦烘櫙瀵硅薄鍜岃交閲忓浘璋辩殑鍏ュ簱銆佹绱€佽瀺鍚堛€侀噸鎺掑簭鍜?evidence_package 杈撳嚭銆?
### 鎺ㄨ崘宸ョ▼鐩綍缁撴瀯

閲嶇偣鍒涘缓 `src/knowledge/`銆乣src/knowledge/ingestion/`銆乣src/knowledge/indexes/`銆乣configs/rag.yaml`銆?
### 闇€瑕佹柊澧炴垨淇敼鐨勬枃浠?
- `src/knowledge/schemas.py`
- `src/knowledge/source_registry.py`
- `src/knowledge/scene_object_registry.py`
- `src/knowledge/ingestion/text_ingestor.py`
- `src/knowledge/ingestion/pdf_ingestor.py`
- `src/knowledge/ingestion/visual_ingestor.py`
- `src/knowledge/ingestion/scene_ingestor.py`
- `src/knowledge/indexes/keyword_index.py`
- `src/knowledge/indexes/vector_index.py`
- `src/knowledge/indexes/hybrid_index.py`
- `src/knowledge/indexes/visual_page_index.py`
- `src/knowledge/indexes/graph_index.py`
- `src/knowledge/retrieval_controller.py`
- `src/knowledge/evidence_package.py`
- `src/knowledge/evidence_gate.py`
- `scripts/ingest_sources.py`
- `tests/integration/rag_pipeline/test_basic_retrieval.py`
- `tests/integration/rag_pipeline/test_scene_binding_retrieval.py`

### 鏍稿績绫汇€佸嚱鏁般€佹ā鍧楁垨鏈嶅姟璁捐

- `SourceRecord`锛歴ource_id銆乻ource_type銆乤uthority_level銆乺eview_status銆乿ersion銆?- `TextChunk`锛氱埗瀛愯妭鐐规枃鏈墖娈点€?- `VisualAsset`锛氬浘鐗囥€佺粨鏋勫浘鍜岀ず鎰忓浘瀵硅薄銆?- `SceneObject`锛?D 鐑偣涓庣煡璇嗗疄浣撶粦瀹氥€?- `RetrievalController`锛氭煡璇㈢悊瑙ｃ€佽鍒掗€夋嫨銆佸璺彫鍥炪€佽瀺鍚堛€?- `EvidenceGate`锛歝onfident銆亀eak銆乧onflict銆乽nclear銆?- `EvidencePackageBuilder`锛氭瀯閫犵粺涓€璇佹嵁鍖呫€?
### 鏁版嵁娴?/ 璋冪敤閾捐矾

1. `ingest_sources.py` 鐧昏 source_registry銆?2. 鏂囨湰/PDF/鍥剧墖/3D 鍏冩暟鎹垎鍒繘鍏ヨВ鏋愬櫒銆?3. 瑙ｆ瀽缁撴灉鍐欏叆鍏抽敭璇嶃€佸悜閲忋€佽瑙夐〉闈㈠拰鍥捐氨绱㈠紩銆?4. 杩愯鏃?`RetrievalController` 鎺ユ敹 query_object銆乻cene_state銆乵emory_context銆?5. 鐢熸垚澶氭煡璇紝鎵ц澶氳矾鍙洖銆?6. RRF 铻嶅悎涓庨噸鎺掑簭銆?7. `EvidenceGate` 鍒ゅ畾璇佹嵁璐ㄩ噺銆?8. 杈撳嚭 `evidence_package`銆?
### 鎺ュ彛璁捐

```text
register_source(source_record) -> source_id
ingest_text(source_id, document) -> list[TextChunk]
register_scene_object(scene_object) -> scene_object_id
plan_retrieval(query_object, scene_state, memory_context, budget) -> RetrievalPlan
retrieve_evidence(retrieval_plan) -> EvidencePackage
evaluate_evidence_package(evidence_package) -> EvidenceGateResult
```

### 閰嶇疆椤硅璁?
- `rag.chunk_size_chars: 300-600`
- `rag.chunk_overlap_chars: 60-100`
- `rag.keyword_top_k`
- `rag.vector_top_k`
- `rag.visual_top_k`
- `rag.parent_top_k`
- `rag.allowed_core_review_status: [reviewed]`
- `rag.evidence_gate_thresholds`

### 閿欒澶勭悊鏂瑰紡

- 鏃?reviewed 璇佹嵁鏃讹紝`EvidenceGate` 杩斿洖 weak 鎴?unclear銆?- 瑙嗚璇佹嵁缂哄皯 text_cross_check 鏃讹紝`usable_as_core_evidence=false`銆?- scene_object_id 鎵句笉鍒版椂杩斿洖婢勬竻寤鸿锛屼笉榛樿缁戝畾銆?
### 鏃ュ織涓庣洃鎺ц姹?
- 璁板綍 retrieval_plan銆乹uery_variants銆乧hannel_hits銆乫iltered_count銆乪vidence_ids銆乬ate_status銆乵issing_evidence銆?- 鐩戞帶 evidence_coverage銆乻cene_match_rate銆乺etrieval_latency_ms銆?
### 娴嬭瘯鏂规

- 鍏ュ簱娴嬭瘯锛歴ource_registry銆乼ext_chunk銆乻cene_object_registry 瀛楁瀹屾暣銆?- 妫€绱㈡祴璇曪細鏈鏌ヨ璧板叧閿瘝锛屽彛璇煡璇㈣蛋鍚戦噺锛屽満鏅寚浠ｈ蛋 scene registry銆?- 闂ㄦ帶娴嬭瘯锛歞raft 璧勬枡涓嶈兘浣滀负鏍稿績璇佹嵁銆?- 澶氭ā鎬佹祴璇曪細瑙嗚鏍囩鏃犳枃鏈牎楠屼笉鑳芥敮鎾戜簨瀹?claim銆?
### 闃舵楠屾敹鏂瑰紡

鐢?C919 鍙戝姩鏈恒€佹満缈煎崌鍔涖€丄G600 鑸瑰瀷鏈鸿韩绛夋紨绀烘暟鎹獙璇?`evidence_package` 鑳芥彁渚涙潵婧愩€佺己澶辫瘉鎹拰鐢熸垚杈圭晫銆?
### 寰呯‘璁?/ 璁捐鍋囪 / 娼滃湪椋庨櫓

- 寰呯‘璁わ細鏈€缁堝悜閲忓簱銆佸浘鏁版嵁搴撱€丳DF 瑙ｆ瀽宸ュ叿鍜?embedding 妯″瀷銆?- 璁捐鍋囪锛歁VP 鍙厛鐢ㄥ唴瀛樻垨鏂囦欢绱㈠紩妯℃嫙锛屾帴鍙ｄ繚鎸佸彲鏇挎崲銆?- 娼滃湪椋庨櫓锛氬伐鍏烽€夊瀷杩囨棭缁戝畾锛屽悗缁毦浠ユ浛鎹€?
## P4锛氬洖绛旂敓鎴愩€佹潵婧愮粦瀹氫笌缁撴瀯鍖栬緭鍑?
### 瀵瑰簲 task.md 涓殑闃舵鐩爣

鍩轰簬浜嬪疄璇佹嵁鍖呯敓鎴愬彲妫€鏌ョ殑 `answer_envelope`锛屽寘鎷煭绛斻€佷富浣撱€佹潵婧愮粦瀹氥€佽瑙夊紩鐢ㄣ€佷笉纭畾鎬с€佸畨鍏ㄨ鏄庡拰鍓嶇灞曠ず鍧椼€?
### 鎺ㄨ崘宸ョ▼鐩綍缁撴瀯

閲嶇偣鍒涘缓 `src/generation/` 鍜?`tests/unit/generation/`銆?
### 闇€瑕佹柊澧炴垨淇敼鐨勬枃浠?
- `src/generation/answer_types.py`
- `src/generation/evidence_sketch.py`
- `src/generation/planner.py`
- `src/generation/generator.py`
- `src/generation/citation_binding.py`
- `src/generation/display_blocks.py`
- `tests/unit/generation/test_answer_type_router.py`
- `tests/unit/generation/test_citation_binding.py`
- `tests/integration/answer_pipeline/test_grounded_answer.py`

### 鏍稿績绫汇€佸嚱鏁般€佹ā鍧楁垨鏈嶅姟璁捐

- `AnswerTypeRouter`锛氶€夋嫨姒傚康瑙ｉ噴銆侀儴浠跺満鏅€佸姣斿綊绾炽€佸弬鏁颁簨瀹炪€佹搷浣滃畨鍏ㄣ€佹緞娓呰拷闂€?- `EvidenceSketchBuilder`锛氭彁鐐?facts銆乺elations銆乿isual_clues銆乵issing_points銆乧onflicts銆?- `GenerationPlanner`锛氱敓鎴?answer_plan銆?- `GroundedAnswerGenerator`锛氱敓鎴愮粨鏋勫寲鍒濈銆?- `CitationBinder`锛氱淮鎶?claim 鍒?evidence_id 鐨勬槧灏勩€?- `DisplayBlockBuilder`锛氱敓鎴?conclusion_block銆乻ource_block銆乻cene_block銆乹uiz_block銆?
### 鏁版嵁娴?/ 璋冪敤閾捐矾

1. 鎺ユ敹 query_object銆乻cene_state銆乵emory_context銆乸rompt_selection銆乪vidence_package銆?2. `AnswerTypeRouter` 鍒ゅ畾鍥炵瓟绫诲瀷銆?3. `EvidenceSketchBuilder` 鍘嬬缉璇佹嵁銆?4. `GenerationPlanner` 浜у嚭鍥炵瓟璁″垝銆?5. `GroundedAnswerGenerator` 鐢熸垚鑽夌銆?6. `CitationBinder` 鐢熸垚 source_binding銆?7. 杈撳嚭 `answer_envelope` 鍜?`generation_trace`銆?
### 鎺ュ彛璁捐

```text
route_answer_type(query_object, evidence_package, scene_state) -> AnswerType
build_evidence_sketch(evidence_package, answer_type) -> EvidenceSketch
generate_answer(plan, evidence_sketch, memory_context, prompt_selection) -> AnswerEnvelope
bind_citations(answer_envelope, evidence_package) -> SourceBinding
build_display_blocks(answer_envelope, scene_state) -> list[DisplayBlock]
```

### 閰嶇疆椤硅璁?
- `generation.max_main_answer_chars`
- `generation.require_source_binding_for_claims`
- `generation.answer_type_policies`
- `generation.stream_low_risk_only`
- `generation.safety_template_id`

### 閿欒澶勭悊鏂瑰紡

- 璇佹嵁鍖?gate 涓?weak/conflict/unclear 鏃朵笉鐢熸垚纭畾鍥炵瓟锛岃浆鎴愯拷闂垨涓嶇‘瀹氳鏄庛€?- 鍙傛暟闂鏃犳潈濞佽瘉鎹椂绂佹杈撳嚭鍏蜂綋鏁板€笺€?- source_binding 缂哄け鏃跺皢璇?claim 鏍囪涓?`claim_candidates.unsupported_pending_check`銆?
### 鏃ュ織涓庣洃鎺ц姹?
- 璁板綍 answer_type銆乼emplate_id銆乪vidence_ids銆乻ource_binding_count銆乽ncertainty_count銆乷utput_length銆乴atency_ms銆?- 鐩戞帶 unsupported_generation_rate銆乧itation_coverage銆乻cene_reference_rate銆?
### 娴嬭瘯鏂规

- 鍙傛暟浜嬪疄娴嬭瘯锛氱己灏戝弬鏁拌瘉鎹椂涓嶈緭鍑烘暟鍊笺€?- 閮ㄤ欢鍦烘櫙娴嬭瘯锛歴cene_state.component_id 蹇呴』杩涘叆绛旀瀵硅薄銆?- 涓€у寲娴嬭瘯锛歭earner_profile 鏀瑰彉琛ㄨ揪锛屼笉鏀瑰彉浜嬪疄銆?- source_binding 娴嬭瘯锛氭瘡涓叧閿?claim 鏈?evidence_id 鎴栦笉纭畾鎬ц鏄庛€?
### 闃舵楠屾敹鏂瑰紡

鐢ㄥ浐瀹?evidence_package 鐢熸垚 C919銆佹満缈煎崌鍔涖€丄G600 涓夌被绛旀锛屾鏌ョ粨鏋勫寲瀛楁銆佹潵婧愮粦瀹氬拰缂哄け璇佹嵁琛ㄨ揪銆?
### 寰呯‘璁?/ 璁捐鍋囪 / 娼滃湪椋庨櫓

- 寰呯‘璁わ細缁撴瀯鍖栬緭鍑哄伐鍏枫€佹ā鍨嬪悕绉般€佹槸鍚﹀惎鐢ㄦ祦寮忋€?- 璁捐鍋囪锛氬厛浣跨敤闈炴祦寮忚緭鍑轰互纭繚鑷鍙帶銆?- 娼滃湪椋庨櫓锛氭祦寮忓洖绛斿湪鑷鍓嶈緭鍑洪珮椋庨櫓浜嬪疄銆?
## P5锛氳嚜鎴戞鏌ャ€佸姩浣滃垎娴佷笌鍥為€€闂幆

### 瀵瑰簲 task.md 涓殑闃舵鐩爣

寤虹珛鐙珛璐ㄩ噺闂搁棬锛岀敓鎴?`check_report` 骞堕┍鍔?PASS銆丷EWRITE_ONLY銆丷ETRIEVE_MORE銆丄SK_CLARIFICATION銆丠UMAN_REVIEW銆丼AFE_RESPONSE銆丼TOP 绛夊洖閫€鍔ㄤ綔銆?
### 鎺ㄨ崘宸ョ▼鐩綍缁撴瀯

閲嶇偣鍒涘缓 `src/self_check/` 鍜岃嚜妫€鐩稿叧闆嗘垚娴嬭瘯銆?
### 闇€瑕佹柊澧炴垨淇敼鐨勬枃浠?
- `src/self_check/claim_extractor.py`
- `src/self_check/evidence_alignment.py`
- `src/self_check/scene_checker.py`
- `src/self_check/multimodal_checker.py`
- `src/self_check/boundary_checker.py`
- `src/self_check/score_card.py`
- `src/self_check/decision_router.py`
- `tests/unit/self_check/test_claim_support.py`
- `tests/unit/self_check/test_decision_router.py`
- `tests/integration/answer_pipeline/test_self_check_loop.py`

### 鏍稿績绫汇€佸嚱鏁般€佹ā鍧楁垨鏈嶅姟璁捐

- `ClaimExtractor`锛氭媶瑙ｄ簨瀹炰富寮犮€?- `EvidenceAligner`锛氭槧灏?claim 鍒?evidence_items銆?- `SceneAlignmentChecker`锛氭鏌?aircraft/component/hotspot 鏄惁涓€鑷淬€?- `MultimodalConsistencyChecker`锛氭鏌?visual_refs銆乥box銆乼ext_cross_check銆?- `BoundaryChecker`锛氭鏌ヨ蹇嗗拰 Prompt 鏄惁瓒婃潈銆?- `ScoreCardBuilder`锛氱敓鎴愬缁磋瘎鍒嗐€?- `DecisionRouter`锛氬皢璇勫垎鍜岄棶棰樻槧灏勪负 action_decision銆?
### 鏁版嵁娴?/ 璋冪敤閾捐矾

1. 鎺ユ敹 answer_envelope銆乪vidence_package銆乻cene_state銆乵emory_context銆乸rompt_asset銆?2. 鎶藉彇 claims銆?3. 鎵ц璇佹嵁瀵归綈銆佸満鏅鏌ャ€佸妯℃€佹鏌ャ€佽竟鐣屾鏌ャ€佸畨鍏ㄦ鏌ャ€?4. 鐢熸垚 score_card銆?5. `DecisionRouter` 杈撳嚭 action_decision 鍜?revised_instruction銆?6. 鐘舵€佹満鎸夊姩浣滃洖鍒?P3銆丳4銆丳6 鎴?final銆?
### 鎺ュ彛璁捐

```text
extract_claims(answer_envelope) -> list[Claim]
align_claims_to_evidence(claims, evidence_package) -> ClaimSupportMap
check_scene_alignment(answer_envelope, scene_state) -> SceneCheckResult
build_score_card(check_results) -> ScoreCard
route_decision(score_card, issue_list, loop_count) -> CheckReport
```

### 閰嶇疆椤硅璁?
- `self_check.thresholds.factual_grounding`
- `self_check.thresholds.scene_alignment`
- `self_check.thresholds.risk_score`
- `self_check.max_retrieve_loops`
- `self_check.max_rewrite_loops`
- `self_check.high_risk_claim_types`

### 閿欒澶勭悊鏂瑰紡

- check_report 缂哄瓧娈垫椂閲嶈瘯鎴栭檷绾т负 HUMAN_REVIEW銆?- 寰幆瓒呰繃涓婇檺鏃惰緭鍑?STOP 鍜屼繚瀹堝洖绛斻€?- 楂橀闄╁畨鍏ㄨ姹傜洿鎺?SAFE_RESPONSE銆?
### 鏃ュ織涓庣洃鎺ц姹?
- 璁板綍 failed_checks銆乽nsupported_claims銆乤ction_decision銆乺evised_instruction銆乴oop_count銆?- 鐩戞帶 hallucination_detection銆乻cene_alignment_fail_count銆乺isk_detection_count銆?
### 娴嬭瘯鏂规

- 鏃犺瘉鎹弬鏁拌Е鍙?RETRIEVE_MORE 鎴?REWRITE_ONLY銆?- 鍦烘櫙瀵硅薄閿欎綅瑙﹀彂 REWRITE_ONLY/ASK_CLARIFICATION銆?- 璁板繂瓒婄晫瑙﹀彂 REWRITE_ONLY銆?- 鍗遍櫓鎿嶄綔瑙﹀彂 SAFE_RESPONSE銆?
### 闃舵楠屾敹鏂瑰紡

鏋勫缓鍥涚被澶辫触鏍蜂緥锛氭棤璇佹嵁鍙傛暟銆侀儴浠堕敊浣嶃€佽繃闅捐〃杈俱€佸嵄闄╂搷浣滐紝纭鍔ㄤ綔鍒嗘祦姝ｇ‘銆?
### 寰呯‘璁?/ 璁捐鍋囪 / 娼滃湪椋庨櫓

- 寰呯‘璁わ細浜哄伐澶嶆牳鍏ュ彛鍜岃瘎鍒嗛槇鍊笺€?- 璁捐鍋囪锛氬厛浠ヨ鍒欎负涓伙紝LLM Judge 鍙鐞嗚涔夋敮鎸佸害銆?- 娼滃湪椋庨櫓锛歀LM Judge 璇垽锛岄渶瑕佷繚鐣欏彲瑙ｉ噴 issue_list銆?
## P6锛氬弽棣堟敼鍐欍€佸杞?checkpoint 涓庤蹇嗗€欓€?
### 瀵瑰簲 task.md 涓殑闃舵鐩爣

澶勭悊鐢ㄦ埛瀵逛笂涓€杞洖绛旂殑鍙嶉锛屽湪璇佹嵁閿佸畾銆佹潵婧愪繚鎸佸拰鑷澶嶆牳涓嬪仛灞€閮ㄦ敼鍐欙紝骞剁敓鎴愬€欓€夎蹇嗐€?
### 鎺ㄨ崘宸ョ▼鐩綍缁撴瀯

閲嶇偣鍒涘缓 `src/feedback/` 鍜?`tests/integration/feedback_loop/`銆?
### 闇€瑕佹柊澧炴垨淇敼鐨勬枃浠?
- `src/feedback/feedback_event.py`
- `src/feedback/parser.py`
- `src/feedback/checkpoint_store.py`
- `src/feedback/evidence_lock.py`
- `src/feedback/rewrite_planner.py`
- `src/feedback/rewriter.py`
- `src/feedback/delta_map.py`
- `tests/unit/feedback/test_feedback_parser.py`
- `tests/unit/feedback/test_evidence_lock.py`
- `tests/integration/feedback_loop/test_rewrite_preserves_sources.py`

### 鏍稿績绫汇€佸嚱鏁般€佹ā鍧楁垨鏈嶅姟璁捐

- `FeedbackParser`锛氳瘑鍒?SIMPLIFY銆丼HORTEN銆丗ORMAT_TRANSFORM銆丗ACT_CHALLENGE銆丼CENE_REBIND銆丳REFERENCE_SIGNAL銆丼AFETY_SENSITIVE銆?- `CheckpointStore`锛氫繚瀛樹笂涓€杞洖绛斻€佽瘉鎹€佽嚜妫€鍜岀姸鎬併€?- `EvidenceLock`锛氶攣瀹氬彲鐢ㄨ瘉鎹拰 claim銆?- `RewritePlanner`锛氱敓鎴愬彲鎵ц鏀瑰啓鎿嶄綔銆?- `ControlledRewriter`锛氱敓鎴?revised_answer_envelope銆?- `DeltaMapper`锛氳褰曞垹鏀瑰宸紓銆?
### 鏁版嵁娴?/ 璋冪敤閾捐矾

1. FINAL_READY 鍚庝繚瀛?checkpoint銆?2. 鏀跺埌 feedback_event銆?3. 瑙ｆ瀽 feedback_intent銆?4. 瀵硅〃杈剧被鍙嶉鎵ц evidence_locking 鍚庢敼鍐欍€?5. 瀵逛簨瀹炴寫鎴樻垨鍦烘櫙閲嶇粦瀹氬洖鍒?P3 琛ユ绱€?6. 鐢熸垚 revised_answer_envelope銆乨elta_map銆乵emory_update_candidate銆?7. 鏀瑰啓鍚庨€?P5 鑷銆?
### 鎺ュ彛璁捐

```text
save_checkpoint(run_id, answer_envelope, evidence_package, check_report) -> checkpoint_id
parse_feedback(feedback_event, checkpoint) -> FeedbackParseResult
build_evidence_lock(checkpoint) -> EvidenceLock
plan_rewrite(parse_result, evidence_lock) -> RewritePlan
rewrite_answer(rewrite_plan, checkpoint) -> RevisedAnswerEnvelope
build_delta_map(previous_answer, revised_answer) -> DeltaMap
```

### 閰嶇疆椤硅璁?
- `feedback.max_rewrite_rounds`
- `feedback.allowed_format_transforms`
- `feedback.preference_stability_threshold`
- `feedback.safety_sensitive_patterns`

### 閿欒澶勭悊鏂瑰紡

- checkpoint 缂哄け鏃惰姹傜敤鎴烽噸鏂拌鏄庨棶棰樻垨闄嶇骇鏅€氶棶绛斻€?- FACT_CHALLENGE 涓嶅厑璁哥洿鎺ユ敼浜嬪疄锛屽繀椤昏缃?verification_queries銆?- 瀹夊叏鏁忔劅鍙嶉鐩存帴 SAFE_RESPONSE銆?
### 鏃ュ織涓庣洃鎺ц姹?
- 璁板綍 feedback_intent銆乺ewrite_action銆乧hanged_claims銆乺emoved_claims銆乤dded_claims銆乸reserved_source_binding銆乻elf_check_result銆?- 鐩戞帶 semantic_preservation銆乧itation_retention銆乽ser_acceptance_signal銆?
### 娴嬭瘯鏂规

- 鈥滃お涓撲笟浜嗏€濆彧闄嶄綆鏈瀵嗗害锛宻ource_binding 淇濈暀銆?- 鈥滀綘璇撮敊浜嗏€濊Е鍙戣ˉ妫€绱㈡垨涓嶇‘瀹氭€ц鏄庛€?- 鈥滅敤琛ㄦ牸鈥濅繚鐣欐棤璇佹嵁缁村害涓衡€滆祫鏂欐湭瑕嗙洊鈥濄€?- 鈥滃叿浣撶淮淇楠も€濊Е鍙?SAFE_RESPONSE銆?
### 闃舵楠屾敹鏂瑰紡

杩愯澶氳疆瀵硅瘽鍦烘櫙锛岀‘璁ゅ弽棣堟敼鍐欎笉浼氫涪璇佹嵁銆佷笉浼氭柊澧炰簨瀹炪€佷笉浼氱洿鎺ュ啓闀挎湡璁板繂銆?
### 寰呯‘璁?/ 璁捐鍋囪 / 娼滃湪椋庨櫓

- 寰呯‘璁わ細鍓嶇鏄惁灞曠ず delta_map銆?- 璁捐鍋囪锛歝heckpoint 鍏堢敤鍐呭瓨鎴栬交閲忓瓨鍌紝鐢熶骇鍐嶆寔涔呭寲銆?- 娼滃湪椋庨櫓锛氱敤鎴疯繛缁弽棣堝鑷村惊鐜欢杩燂紝蹇呴』鎵ц max_rewrite_rounds銆?
## P7锛氳闊充氦浜掍笌瀹炴椂浼氳瘽

### 瀵瑰簲 task.md 涓殑闃舵鐩爣

瀹炵幇绾ц仈寮?Voice RAG锛歏AD -> ASR -> 鏌ヨ鏍囧噯鍖?-> RAG -> 鍥炵瓟鐢熸垚 -> 鑷垜妫€鏌?-> TTS锛屽苟鏀寔瀹炴椂鎵撴柇鍜岃闊冲弽棣堛€?
### 鎺ㄨ崘宸ョ▼鐩綍缁撴瀯

閲嶇偣鍒涘缓 `src/voice/`銆乣configs/voice.yaml` 鍜?`tests/integration/voice_loop/`銆?
### 闇€瑕佹柊澧炴垨淇敼鐨勬枃浠?
- `src/voice/transport.py`
- `src/voice/session_state.py`
- `src/voice/vad.py`
- `src/voice/asr.py`
- `src/voice/terminology.py`
- `src/voice/tts.py`
- `src/voice/barge_in.py`
- `src/voice/metrics.py`
- `src/input/voice_query_normalizer.py`
- `configs/voice.yaml`
- `tests/unit/voice/test_voice_state_machine.py`
- `tests/unit/voice/test_query_normalizer.py`
- `tests/integration/voice_loop/test_barge_in_feedback.py`

### 鏍稿績绫汇€佸嚱鏁般€佹ā鍧楁垨鏈嶅姟璁捐

- `VoiceSessionStateMachine`锛氳闊崇姸鎬佹満銆?- `AudioTransport`锛歐ebSocket/WebRTC 鎶借薄銆?- `VADService`锛歷oice_start銆乿oice_active銆乿oice_pause銆乿oice_end銆?- `ASRProvider`锛氳浆鍐欐帴鍙ｃ€?- `AviationTermCorrector`锛氳埅绌烘湳璇籂閿欍€?- `VoiceQueryNormalizer`锛氳緭鍑?voice_query_object銆?- `TTSProvider`锛氳闊冲悎鎴愭帴鍙ｃ€?- `BargeInController`锛氭墦鏂帶鍒躲€?- `VoiceMetricsRecorder`锛氳闊虫寚鏍囪褰曘€?
### 鏁版嵁娴?/ 璋冪敤閾捐矾

1. 鍓嶇鍙戦€侀煶棰戝抚鍜?scene_state銆?2. VAD 鍒ゆ柇寮€濮嬨€佸仠椤裤€佺粨鏉熴€?3. ASR 杈撳嚭 raw_transcript銆乧onfidence銆乸artial/final銆?4. 鏈绾犻敊涓庡彛璇爣鍑嗗寲鐢熸垚 voice_query_object銆?5. 浣庣疆淇℃垨缂哄満鏅璞℃椂 ASK_CLARIFICATION銆?6. 姝ｅ父鏌ヨ杩涘叆 P1/P3/P4/P5 涓婚摼璺€?7. answer_envelope 杞?spoken_answer銆?8. TTS 鎾姤鏃剁洃鍚?barge_in銆?9. 鎵撴柇浜嬩欢杩涘叆 P6 鍙嶉鏀瑰啓銆?
### 鎺ュ彛璁捐

```text
handle_audio_frame(session_id, frame, scene_state) -> VoiceEvent
transcribe(audio_segment) -> ASRResult
correct_terms(asr_result, scene_state, term_lexicon) -> CorrectedTranscript
normalize_voice_query(corrected_transcript, scene_state, dialogue_context) -> VoiceQueryObject
synthesize_spoken_answer(answer_envelope, tts_config) -> AudioStream
handle_barge_in(session_id, partial_feedback) -> FeedbackEvent
```

### 閰嶇疆椤硅璁?
- `voice.transport: websocket|webrtc`
- `voice.sample_rate`
- `voice.vad_provider`
- `voice.asr_provider`
- `voice.tts_provider`
- `voice.asr_low_confidence_threshold`
- `voice.max_spoken_answer_seconds`
- `voice.barge_in_priority`
- `voice.pronunciation_lexicon_path`

### 閿欒澶勭悊鏂瑰紡

- ASR 浣庣疆淇℃椂锛屼笉杩涘叆 RAG锛岃繑鍥炴緞娓呫€?- TTS 鎾姤澶辫触鏃朵繚鐣欐枃瀛楀洖绛旓紝涓嶉噸璺戠敓鎴愩€?- barge_in 蹇呴』鍏堝仠姝㈡棫闊抽锛屽啀瑙ｆ瀽鍙嶉銆?
### 鏃ュ織涓庣洃鎺ц姹?
- 璁板綍 ASR confidence銆乂AD 璇垏銆侀鍝嶅欢杩熴€乀TS 棣栧寘寤惰繜銆乥arge_in 鍝嶅簲鏃堕棿銆乻poken_answer 闀垮害銆?- 涓嶅湪鏅€氭棩蹇椾繚瀛樺師濮嬮煶棰戯紱濡傞渶淇濆瓨锛屽繀椤诲彈 policy_memory 鎺у埗銆?
### 娴嬭瘯鏂规

- 鈥滄兜閬撴瘮鈥濊璇嗗埆涓衡€滆埅閬撴瘮鈥濇椂杩涘叆鏈绾犻敊鎴栨緞娓呫€?- 鐢ㄦ埛璇粹€滆繖涓槸浠€涔堚€濅絾鏃?scene_object_id 鏃?ASK_CLARIFICATION銆?- TTS 鎾姤涓敤鎴疯鈥滃仠涓€涓嬶紝璁茬畝鍗曠偣鈥濇椂瑙﹀彂 barge_in 鍜?P6 鏀瑰啓銆?- spoken_answer 涓嶈秴杩囬厤缃暱搴︺€?
### 闃舵楠屾敹鏂瑰紡

瀹屾垚涓€涓?WebSocket 璇煶鍘熷瀷锛岃兘灏嗘ā鎷熻闊宠浆鍐欒緭鍏ユ爣鍑嗗寲涓?query_object锛屽苟鑳藉湪 TTS 鏈熼棿澶勭悊涓柇銆?
### 寰呯‘璁?/ 璁捐鍋囪 / 娼滃湪椋庨櫓

- 寰呯‘璁わ細璇煶 Provider銆乄ebRTC 鏄惁蹇呴』銆侀儴缃茬綉缁滄潯浠躲€?- 璁捐鍋囪锛氬厛鐢?WebSocket 鍜屾ā鎷?ASR/TTS 閫氳繃鐘舵€佹満锛屽啀鎺ョ湡瀹?Provider銆?- 娼滃湪椋庨櫓锛氱鍒扮璇煶妯″瀷缁曡繃璇佹嵁閾撅紝蹇呴』绂佹鍏剁洿鎺ョ敓鎴愪簨瀹炲洖绛斻€?
## P8锛氭棩蹇楄瘎娴嬨€佹紨绀哄璁′笌閮ㄧ讲娌荤悊

### 瀵瑰簲 task.md 涓殑闃舵鐩爣

寤虹珛璇勬祴銆佹棩蹇椼€佸璁°€侀儴缃插拰婕旂ず浣撶郴锛屼娇绯荤粺鑳借瘉鏄庘€滄湁璇佹嵁鍥炵瓟銆佽蹇嗗彈鎺с€佽嚜妫€鏈夋晥銆佸弽棣堝彲杩借釜銆佽闊冲彲鎵撴柇鈥濄€?
### 鎺ㄨ崘宸ョ▼鐩綍缁撴瀯

閲嶇偣鍒涘缓 `scripts/`銆乣docs/`銆乣tests/e2e/` 鍜岃瘎娴嬮厤缃€?
### 闇€瑕佹柊澧炴垨淇敼鐨勬枃浠?
- `configs/evals.yaml`
- `scripts/run_eval.py`
- `scripts/seed_demo_data.py`
- `scripts/export_trace_report.py`
- `docs/architecture.md`
- `docs/api_contracts.md`
- `docs/eval_plan.md`
- `tests/e2e/scenarios/test_text_qa_flow.py`
- `tests/e2e/scenarios/test_feedback_flow.py`
- `tests/e2e/scenarios/test_voice_flow.py`

### 鏍稿績绫汇€佸嚱鏁般€佹ā鍧楁垨鏈嶅姟璁捐

- `EvaluationDataset`锛氬浐瀹氳瘎娴嬫牱渚嬨€?- `RunTraceExporter`锛氬鍑烘瘡杞繍琛岃瘉鎹摼銆?- `MetricsAggregator`锛氭眹鎬绘绱€佺敓鎴愩€佽嚜妫€銆佸弽棣堛€佽蹇嗐€佽闊虫寚鏍囥€?- `DemoReportBuilder`锛氱敓鎴愮瓟杈╁睍绀烘姤鍛娿€?- `DeploymentConfigValidator`锛氭鏌ラ儴缃查厤缃畬鏁存€с€?
### 鏁版嵁娴?/ 璋冪敤閾捐矾

1. `seed_demo_data.py` 鍐欏叆婕旂ず璧勬枡銆佸満鏅璞″拰娴嬭瘯鐢ㄦ埛銆?2. `run_eval.py` 鎵ц鍥哄畾鍦烘櫙銆?3. 姣忚疆鏀堕泦 run_trace銆乧heck_report銆乺ewrite_log銆乿oice_metric_record銆?4. `MetricsAggregator` 杈撳嚭鎸囨爣銆?5. `export_trace_report.py` 瀵煎嚭鍙璁℃姤鍛娿€?
### 鎺ュ彛璁捐

```text
run_eval_suite(suite_name, config) -> EvaluationReport
aggregate_metrics(run_traces) -> MetricsSummary
export_trace_report(run_id, format="markdown") -> ReportPath
validate_deployment_config(configs) -> ValidationReport
```

### 閰嶇疆椤硅璁?
- `evals.suites`
- `evals.required_pass_rate`
- `evals.latency_budget_ms`
- `evals.trace_export_fields`
- `deployment.required_env`

### 閿欒澶勭悊鏂瑰紡

- 璇勬祴鏍蜂緥缂鸿瘉鎹椂鏍囪涓烘暟鎹棶棰橈紝涓嶅綊鍜庢ā鍨嬨€?- 鎸囨爣浣庝簬闃堝€兼椂闃绘闃舵楠屾敹銆?- 閮ㄧ讲閰嶇疆缂哄け鏃惰緭鍑烘槑纭己澶遍」銆?
### 鏃ュ織涓庣洃鎺ц姹?
- 鍏ㄩ摼璺棩蹇楃粺涓€ run_id銆?- 璇勬祴鎶ュ憡蹇呴』鑳藉畾浣嶅埌澶辫触闃舵銆佸け璐ュ璞″拰澶辫触鍘熷洜銆?- 婕旂ず瀵煎嚭闇€瑕佽劚鏁忕敤鎴烽殣绉佸拰鍘熷闊抽銆?
### 娴嬭瘯鏂规

- E2E 鏂囨湰闂瓟锛氳緭鍏?-> RAG -> 鐢熸垚 -> 鑷 -> 杈撳嚭銆?- E2E 鍙嶉鏀瑰啓锛氫笂涓€杞洖绛?-> 鐢ㄦ埛鍙嶉 -> 鏀瑰啓 -> 鑷銆?- E2E 璇煶锛欰SR 妯℃嫙 -> 鏍囧噯鍖?-> 涓婚摼璺?-> TTS -> barge_in銆?- 鍥炲綊娴嬭瘯锛氬弬鏁版棤璇佹嵁銆佸満鏅敊浣嶃€佸畨鍏ㄦ晱鎰熴€丄SR 浣庣疆淇°€?
### 闃舵楠屾敹鏂瑰紡

涓€鏉″懡浠ゅ彲杩愯璇勬祴濂椾欢骞跺鍑烘姤鍛娿€傛姤鍛婃樉绀烘瘡涓満鏅殑璇佹嵁鍛戒腑銆佽嚜妫€鍔ㄤ綔銆佹渶缁堣緭鍑哄拰澶辫触椤广€?
### 寰呯‘璁?/ 璁捐鍋囪 / 娼滃湪椋庨櫓

- 寰呯‘璁わ細姝ｅ紡閮ㄧ讲鏂瑰紡銆佹紨绀虹幆澧冦€佽瘎娴嬮泦瑙勬ā銆侀殣绉佽劚鏁忔爣鍑嗐€?- 璁捐鍋囪锛氬厛鏈湴閮ㄧ讲鍜屾紨绀烘姤鍛婏紝鍚庣画鍐?CI/CD銆?- 娼滃湪椋庨櫓锛氬姛鑳藉凡瀹屾垚浣嗘病鏈夊彲閲嶅璇勬祴锛屽鑷村悗缁敼鍔ㄦ棤娉曢槻鍥炲綊銆?
```

## docs/harness.md

```
# 缈艰鏃犱綑 AI 鏅鸿兘瀵煎笀绯荤粺寮€鍙戠害鏉熶笌楠屾敹 Harness

## 鏂囨。渚濇嵁

鏈枃涓?`task.md`銆乣spec.md` 浣跨敤鍚屼竴濂?P0 鍒?P8 闃舵缂栧彿锛岀敤浜庣害鏉?Codex 鎴栧紑鍙戣€呭湪瀹炵幇绯荤粺鏃朵笉鑳藉亸绂汇€婄考瑙堟棤浣欐櫤鑳戒綋妯″潡璁捐鏂囨。V2_澧炲姞璇煶浜や簰.docx銆嬬殑鏍稿績璁捐銆傛湰鏂囬噸鐐瑰洖绛旓細鍝簺鏂囦欢鍙互鏀广€佸摢浜涜竟鐣屼笉鑳界牬銆佸摢浜涙祴璇曞繀椤昏繃銆佸浣曢槻姝?Agent 璋冨害銆佺煡璇嗗簱銆佽蹇嗙郴缁熴€佷笟鍔℃ā鍧楁贩鍐欍€?
## 闇€瑕佽皟鐢ㄧ殑 skill

璁?Codex 渚濇嵁鏈枃鎵ц寮€鍙戞椂锛屼笉瑕佹眰姣忎釜闃舵棰勫厛鍥哄畾 skill锛岃€屾槸鍏堣皟鐢?`using-superpowers`锛岀敱瀹冩牴鎹綋鍓嶉樁娈典换鍔°€侀闄┿€佸け璐ョ姸鎬佸拰鍙敤宸ュ叿鍔ㄦ€佸垽鏂繕闇€瑕佸摢浜?skill銆備负婊¤冻宸ヤ綔鍖?AGENTS.md 涓€淐odex 鎻愮ず璇嶅繀椤绘槑纭０鏄?skill鈥濈殑瑕佹眰锛屾湰鎻愮ず璇嶅彧澹版槑鍏ㄥ眬蹇呴渶 skill锛?
1. `using-superpowers`锛氭瘡娆″紑濮嬮樁娈靛疄鐜版垨淇鍓嶅厛璋冪敤锛岀敤浜庡彂鐜板苟璋冨害褰撳墠闃舵鐪熸闇€瑕佺殑 skill銆?2. `writing-plans`锛氬綋闃舵娑夊強澶氭枃浠躲€佸妯″潡鎴栧姝ラ瀹炵幇鏃惰皟鐢紝鐢ㄤ簬鍒楀嚭鍙牳鏌ヨ鍒掑拰鏂囦欢鑼冨洿銆?3. `systematic-debugging`锛氫换浣曟祴璇曞け璐ャ€侀摼璺紓甯搞€佽緭鍑哄亸绂绘垨绾夸笂鐥囩姸澶嶇幇鏃惰皟鐢ㄣ€?4. `verification-before-completion`锛氶樁娈靛畬鎴愬墠蹇呴』璋冪敤锛岀敤浜庤繍琛屾祴璇曘€佸绾︽鏌ュ拰鏂囨。涓€鑷存€ф鏌ャ€?5. `openai-docs`锛氫粎褰撻樁娈垫秹鍙?OpenAI 璇煶銆丷ealtime銆佺粨鏋勫寲杈撳嚭銆佹ā鍨嬫垨 API 鍙樺寲鏃惰皟鐢紝鐢ㄤ簬鏌ヨ瀹樻柟鏈€鏂版枃妗ｃ€?
闄や笂杩板叏灞€瑙勫垯澶栵紝鏈枃涓嶅湪姣忎釜闃舵鍗曠嫭鎸囧畾 skill銆傞樁娈垫墽琛岃€呭繀椤诲湪杩涘叆鍏蜂綋瀹炵幇鍓嶄緷鎹?`using-superpowers` 鐨勫垽鏂姩鎬佽ˉ鍏呰皟鐢ㄧ浉鍏?skill锛屽苟鍦ㄩ樁娈甸獙鏀朵腑璁板綍瀹為檯璋冪敤杩囩殑鍏抽敭 skill銆?
## P1 Prompt Refactor Update

鐢变簬鏃ф枃妗ｉ《閮ㄥ瓨鍦ㄤ贡鐮侊紝鏈妭鍗曠嫭閲嶈堪褰撳墠瀹炵幇涓?P1 Prompt 鐩稿叧鐨勫彲瀹¤杈圭晫锛涙湰鑺傚彧鍋氭緞娓咃紝涓嶆柊澧炲畨鍏ㄨ寖鍥淬€?
- `src/prompts/repository.py` 鏇夸唬宸插垹闄ょ殑 `src/prompts/asset_store.py`銆?- Prompt 璧勪骇鍙兘浠?`assets/prompts/<template_id>/asset.json`銆乣versions/*.json`銆乣evaluations/*.json` 鍔犺浇銆?- `PromptAssetRepository` 鏄敮涓€鍏佽鐨?Prompt 璧勪骇/鐗堟湰/蹇収鍔犺浇鍏ュ彛锛涜繍琛屾€?`active` 鍜?`experimental` 鐗堟湰蹇呴』鏈?approved evaluation snapshot锛屼笖蹇収 `snapshot_id` 蹇呴』涓庣増鏈?`snapshot_id` 瀹屽叏鍖归厤銆?- `PromptAssembler` 蹇呴』杈撳嚭 canonical `PromptMessageBundle`锛屼笉寰楀啀瀹氫箟鎴栬繑鍥炵嫭绔?`MessageBundle`銆?- bundle 涓殑娑堟伅蹇呴』鏄?`services.model_client.ModelMessage`锛岃鑹插彧鑳芥槸 `system` 涓?`user`銆?- 鏅€?trace 鍙褰?template id銆乿ersion銆乻napshot id銆乵issing variables銆乺oute reason 鍜?injection summary锛屼絾涓嶅緱璁板綍瀹屾暣 prompt 姝ｆ枃銆?- Prompt 璧勪骇鍙礋璐?instruction style銆乼eaching style 鍜?output behavior锛涜埅绌轰簨瀹炲繀椤荤户缁潵鑷?`evidence_package`銆?- 鏃у钩閾?YAML 璧勪骇銆乣PromptAssetStore`銆乣default_prompt_assets` 鍜屼换浣?`role="context"` / `role: context` 缁勮鏂瑰紡鍧囦负绂佹椤广€?
## 鍏ㄥ眬瀹夊叏杈圭晫

- 绂佹缁曡繃 `evidence_package` 鐩存帴鐢熸垚鑸┖浜嬪疄銆?- 绂佹鎶婄敤鎴疯蹇嗐€佺敤鎴峰弽棣堛€丳rompt 璧勪骇鎴栨ā鍨嬪父璇嗗綋浣滆埅绌轰簨瀹炴潵婧愩€?- 绂佹灏嗗妯″潡閫昏緫娣峰啓鍒板崟涓€宸ㄥ瀷鏂囦欢涓€?- 绂佹纭紪鐮佹牳蹇冧笟鍔¤鍒欍€侀槇鍊笺€丳rovider銆佹ā鍨嬪悕銆乼op_k銆丳rompt 鍐呭鍜屽畨鍏ㄧ瓥鐣ャ€?- 绂佹鐮村潖 `scene_state`銆乣memory_context`銆乣evidence_package`銆乣answer_envelope`銆乣check_report`銆乣rewrite_plan`銆乣run_trace` 绛夌粺涓€濂戠害銆?- 绂佹寮曞叆 Word 璁捐鏂囨。娌℃湁渚濇嵁鐨勫ぇ鍨嬩緷璧栵紱纭渶寮曞叆鏃跺繀椤绘爣璁?`寰呯‘璁 骞惰鏄庣敤閫斻€佹浛浠ｆ柟妗堝拰鍥炴粴鏂瑰紡銆?- 绂佹璁╄闊崇鍒扮妯″瀷缁曡繃 RAG銆佽嚜妫€鍜屾棩蹇楀璁＄洿鎺ュ洖绛斾簨瀹為棶棰樸€?- 绂佹璺宠繃娴嬭瘯銆佹棩蹇椼€佸紓甯稿鐞嗗拰閰嶇疆绠＄悊銆?
## 鍏ㄥ眬楠屾敹鍛戒护寤鸿

寰呴」鐩剼鎵嬫灦寤虹珛鍚庯紝姣忛樁娈佃嚦灏戞彁渚涗互涓嬬瓑浠峰懡浠わ細

```text
pytest tests/unit
pytest tests/integration
pytest tests/e2e
python scripts/run_eval.py --suite smoke
python scripts/export_trace_report.py --run-id <run_id>
```

濡傛灉鏌愰樁娈靛皻鏈叿澶囧畬鏁存祴璇曞懡浠わ紝蹇呴』鍦ㄩ樁娈甸獙鏀朵腑鏍囪 `寰呯‘璁锛屽苟鑷冲皯鎻愪緵 schema 鏍￠獙銆佸崟鍏冩祴璇曟垨鎵嬪伐楠屾敹璁板綍銆?
## P0锛氬伐绋嬮鏋躲€佺粺涓€鐘舵€佹満涓庢暟鎹绾?
### 绾︽潫瀵硅薄

绾︽潫 `spec.md` 涓?P0 鐨勫伐绋嬮鏋躲€佹暟鎹绾︺€佺姸鎬佹満銆佸姩浣滄灇涓俱€侀厤缃拰鍩虹鏃ュ織瀹炵幇銆?
### 鍏佽淇敼鐨勬枃浠惰寖鍥?
- `pyproject.toml`
- `.env.example`
- `configs/app.yaml`
- `configs/providers.yaml`
- `src/core/**`
- `tests/unit/core/**`
- `README.md`

### 绂佹淇敼鐨勬枃浠惰寖鍥?
- P1 鍒?P8 灏氭湭鍒涘缓鐨勪笟鍔℃ā鍧椾笉寰楀鍏?P0銆?- 涓嶅緱淇敼鍘熷 Word 鏂囨。鍜岀敤鎴锋彁渚涜祫鏂欍€?- 涓嶅緱灏嗘紨绀烘暟鎹€丳rompt 妯℃澘銆佽埅绌虹煡璇嗗簱鍐呭鍐欏叆 `src/core/`銆?
### 绂佹寮曞叆鐨勮璁″亸宸?
- 绂佹姣忎釜妯″潡鑷畾涔夌姸鎬佹灇涓炬垨鍔ㄤ綔鏋氫妇銆?- 绂佹鎶婄姸鎬佹満鍐欐垚涓嶅彲鍥炴斁鐨?if/else 鏁ｉ€昏緫銆?- 绂佹鎶婇厤缃粯璁ゅ€兼暎钀藉湪涓氬姟浠ｇ爜涓€?
### 蹇呴』閬靛畧鐨勬帴鍙ｈ竟鐣?
- 鎵€鏈夐樁娈靛繀椤诲紩鐢?`core.actions.ActionDecision`銆?- 鎵€鏈夐樁娈靛繀椤婚€氳繃 `core.state_machine` 璁板綍鐘舵€佽烦杞€?- 鎵€鏈夎法妯″潡瀵硅薄蹇呴』缁ф壙鎴栭伒瀹?`core.contracts`銆?
### 蹇呴』閫氳繃鐨勬祴璇?
- 鐘舵€佹灇涓惧畬鏁存€ф祴璇曘€?- 闈炴硶鐘舵€佽烦杞け璐ユ祴璇曘€?- `RunTrace` 鏈€灏忓瓧娈垫牎楠屾祴璇曘€?- 閰嶇疆鍔犺浇缂哄け椤规祴璇曘€?
### 蹇呴』婊¤冻鐨勬棩蹇椼€佸紓甯搞€侀厤缃姹?
- 鐘舵€佽烦杞繀椤昏褰?run_id銆乫rom_state銆乼o_state銆乺eason銆?- schema 閿欒蹇呴』鍖呭惈瀛楁璺緞銆?- 閰嶇疆缂哄け涓嶅緱闈欓粯浣跨敤涓氬姟榛樿鍊笺€?
### 瀹夊叏杈圭晫

P0 涓嶆帴瑙︾敤鎴风湡瀹炴暟鎹€佽闊炽€佺煡璇嗗簱鍜?Prompt 鍐呭锛屽彧瀹氫箟妗嗘灦銆?
### 鍥炴粴绛栫暐

濡傛灉 P0 濂戠害璁捐閿欒锛屽簲鍏堟柊澧炲吋瀹瑰瓧娈靛拰杩佺Щ娴嬭瘯锛屽啀閫愭鏇挎崲锛涗笉寰楃洿鎺ュ垹闄ゅ凡琚悗缁樁娈靛紩鐢ㄧ殑瀛楁銆?
### 闃舵瀹屾垚鍓嶆鏌ユ竻鍗?
- [ ] `ActionDecision` 鍖呭惈 Word 鏂囨。瀹氫箟鐨勫叓绫诲姩浣溿€?- [ ] 鐘舵€佹満瑕嗙洊涓婚摼璺拰鍥為€€閾捐矾銆?- [ ] `RunTrace` 鍙叧鑱旇緭鍏ャ€佹绱€佺敓鎴愩€佽嚜妫€銆佸弽棣堝拰鏈€缁堣緭鍑恒€?- [ ] 閰嶇疆涓嶅惈纭紪鐮佸瘑閽ャ€?- [ ] core 鍗曟祴閫氳繃銆?
## P1锛氳緭鍏ョ悊瑙ｃ€佸満鏅姸鎬佷笌 Prompt 璺敱

### 绾︽潫瀵硅薄

绾︽潫 `spec.md` 涓?P1 鐨?`query_object`銆佸満鏅粦瀹氥€丳rompt 璧勪骇妯″瀷銆丳rompt 璺敱鍜屾敞鍏ラ『搴忋€?
### 鍏佽淇敼鐨勬枃浠惰寖鍥?
- `src/input/**`
- `src/prompts/**`
- `configs/prompts.yaml`
- `tests/unit/prompts/**`
- `tests/unit/core/test_contracts.py`

### 绂佹淇敼鐨勬枃浠惰寖鍥?
- 涓嶅緱鍦?P1 淇敼 `src/knowledge/**` 浠ョ粫杩囨绱€?- 涓嶅緱鍦?P1 鍐欏叆闀挎湡璁板繂銆?- 涓嶅緱鍦?Prompt 妯″潡涓啓鑸┖浜嬪疄搴撳唴瀹广€?
### 绂佹寮曞叆鐨勮璁″亸宸?
- 绂佹鐢ㄤ竴涓竾鑳?Prompt 澶勭悊鎵€鏈変换鍔＄被鍨嬨€?- 绂佹鎶婃煇娆¤繍琛岀殑鍏蜂綋椋炴満銆侀儴浠躲€佺敤鎴峰亸濂藉浐鍖栧埌 Prompt 妯℃澘銆?- 绂佹璁?Prompt 璧勪骇琛ュ厖 RAG 娌℃湁鐨勪簨瀹炪€?- 绂佹 candidate/draft Prompt 杩涘叆姝ｅ紡鍥炵瓟閾捐矾銆?
### 蹇呴』閬靛畧鐨勬帴鍙ｈ竟鐣?
- `QueryObject` 鍙〃杈剧敤鎴锋剰鍥惧拰鍦烘櫙缁戝畾锛屼笉鐢熸垚浜嬪疄绛旀銆?- `PromptRouter` 鍙兘閫夋嫨鍜岀粍瑁呮ā鏉匡紝涓嶈闂煡璇嗗簱搴曞眰绱㈠紩銆?- `PromptAssembler` 蹇呴』淇濊瘉 RAG 璇佹嵁鍦?Prompt 璧勪骇涔嬪墠娉ㄥ叆銆?
### 蹇呴』閫氳繃鐨勬祴璇?
- 缂哄け蹇呭～鍙橀噺鏃堕檷绾у熀纭€妯℃澘銆?- 闈?active Prompt 涓嶈兘琚寮忚矾鐢便€?- 鎸囦唬璇嶅湪 scene_object_id 鏄庣‘鏃舵纭粦瀹氥€?- 澶氬€欓€夋寚浠ｆ椂杩斿洖 `needs_clarification`銆?
### 蹇呴』婊¤冻鐨勬棩蹇椼€佸紓甯搞€侀厤缃姹?
- 璁板綍 selected_template_id銆乫allback_reason銆乵issing_variables銆?- Prompt 鐘舵€侀潪娉曟椂鎶涘嚭鏄庣‘閿欒銆?- 妯℃澘鍙橀噺濂戠害蹇呴』鍙厤缃拰鍙祴璇曘€?
### 瀹夊叏杈圭晫

Prompt 妯″潡鍙兘绠＄悊鈥滄€庝箞璁测€濓紝涓嶈兘鍐冲畾鈥滀簨瀹炴槸浠€涔堚€濄€傜敤鎴烽棶棰樹腑鐨勪簨瀹炵寽娴嬩笉寰楄繘鍏?Prompt 璧勪骇姝ｆ枃銆?
### 鍥炴粴绛栫暐

Prompt 鏂扮増鏈嚭鐜拌川閲忎笅闄嶆椂锛屽皢璧勪骇鐘舵€佹敼涓?`deprecated` 鎴栧洖婊氬埌 parent_version锛屼笉鍒犻櫎鍘嗗彶鐗堟湰銆?
### 闃舵瀹屾垚鍓嶆鏌ユ竻鍗?
- [ ] 娉ㄥ叆椤哄簭绗﹀悎 Word 鏂囨。锛氫簨瀹炶竟鐣屻€佷换鍔°€佸満鏅€丷AG銆佽蹇嗐€佽杽寮辩偣銆丳rompt銆佽緭鍑哄绾︺€?- [ ] `{{rag_evidence}}` 缂哄け鏃朵笉浼氱敓鎴愪簨瀹炲瀷鏈€缁?Prompt銆?- [ ] Prompt 璧勪骇鍖呭惈鐘舵€併€佺増鏈€佸彉閲忋€侀闄╄竟鐣屻€?- [ ] prompt 鍗曟祴閫氳繃銆?
## P2锛氳蹇嗙郴缁熶笌鏃堕棿鏈夋晥鎬ф不鐞?
### 绾︽潫瀵硅薄

绾︽潫 `spec.md` 涓?P2 鐨勪簨浠舵棩蹇椼€佺粨鏋勫寲璁板繂銆佹椂闂存湁鏁堟€с€佽蹇嗚鍐欐潈闄愩€佽蹇嗕笂涓嬫枃鍜屽璁°€?
### 鍏佽淇敼鐨勬枃浠惰寖鍥?
- `src/memory/**`
- `configs/memory.yaml`
- `tests/unit/memory/**`
- `tests/integration/answer_pipeline/**` 涓笌 memory_context 鐩稿叧鐨勬祴璇?
### 绂佹淇敼鐨勬枃浠惰寖鍥?
- 涓嶅緱淇敼 `src/knowledge/source_registry.py` 鎴栫煡璇嗗簱浜嬪疄琛ㄦ潵鍐欏叆鐢ㄦ埛璇存硶銆?- 涓嶅緱鍦ㄨ蹇嗘ā鍧椾腑鐢熸垚鏈€缁堝洖绛斻€?- 涓嶅緱璁╀换鎰?Agent 鐩存帴鍐欓暱鏈熻蹇嗚€屼笉璧?`MemoryController`銆?
### 绂佹寮曞叆鐨勮璁″亸宸?
- 绂佹鎶婂畬鏁磋亰澶╄褰曟棤宸埆濉炶繘闀挎湡璁板繂銆?- 绂佹鐢ㄦ埛璇В姹℃煋鑸┖浜嬪疄搴撱€?- 绂佹杩囨湡銆佷綆缃俊銆侀珮闅愮璁板繂榛樿杩涘叆鐢熸垚涓婁笅鏂囥€?- 绂佹鑷垜鍙嶆€濇ā鍧楁壙鎷呰繍琛屾椂鏈€缁堣川閲忚鍐炽€?
### 蹇呴』閬靛畧鐨勬帴鍙ｈ竟鐣?
- 璁板繂鍐欏叆蹇呴』閬靛惊锛氫簨浠舵棩蹇?-> 鍊欓€夋娊鍙?-> 鏃堕棿褰掍竴 -> 鍐茬獊妫€娴?-> 娌荤悊妫€鏌?-> 鍒嗗眰瀛樺偍銆?- `memory_context` 鍙兘褰卞搷琛ㄨ揪銆佹寚浠ｅ拰鏁欏璺緞銆?- `policy_memory` 浼樺厛绾︽潫鎵€鏈夎蹇嗚鍐欍€?
### 蹇呴』閫氳繃鐨勬祴璇?
- 浜嬩欢鍐欏叆鍚庢墠鑳界敓鎴愬€欓€夎蹇嗐€?- 鍐茬獊鍋忓ソ浣跨敤 supersedes/superseded_by 琛ㄨ揪銆?- 杩囨湡璁板繂涓嶈繘鍏?`memory_context`銆?- 鐢ㄦ埛鍙嶉涓殑鑸┖浜嬪疄涓嶈兘杩涘叆鐭ヨ瘑浜嬪疄搴撱€?
### 蹇呴』婊¤冻鐨勬棩蹇椼€佸紓甯搞€侀厤缃姹?
- 姣忔鍐欏叆璁板綍 source_event_id銆乧onfidence銆乸rivacy_level銆乻tatus銆乺eason銆?- 鎷掔粷鍐欏叆蹇呴』鍙璁°€?- 琛板噺銆佽繃鏈熴€佸啿绐佹壂鎻忛槇鍊煎繀椤婚厤缃寲銆?
### 瀹夊叏杈圭晫

鍘熷璇煶銆佸涔犺建杩广€侀殣绉佸亸濂藉拰瀵煎嚭鎶ュ憡蹇呴』閬靛畧鐢ㄦ埛鎺堟潈銆傛湭纭鐨?ASR 缁撴灉鍙兘淇濈暀鍦ㄤ簨浠舵棩蹇椼€?
### 鍥炴粴绛栫暐

璁板繂鍐欓敊鏃朵笉寰楃墿鐞嗗垹闄ょ涓€閫夋嫨锛屽簲灏嗙姸鎬佹敼涓?`rejected`銆乣superseded` 鎴?`archived`锛屼繚鐣欏璁¤褰曘€傜敤鎴疯姹傚垹闄ゆ椂鎸夐殣绉佺瓥鐣ユ墽琛岀‖鍒犻櫎鎴栬劚鏁忋€?
### 闃舵瀹屾垚鍓嶆鏌ユ竻鍗?
- [ ] `MemoryController` 鏄敮涓€闀挎湡鍐欏叆鍏ュ彛銆?- [ ] 璁板繂鐘舵€佹満鍖呭惈 candidate銆乤ctive銆乻tale銆乻uperseded銆乪xpired銆乺ejected銆乤rchived銆?- [ ] `memory_context` 鏄庣‘鍒楀嚭 prohibited_memories銆?- [ ] memory 鍗曟祴閫氳繃銆?
## P3锛氭湰鍦拌埅绌虹煡璇嗗簱銆丷AG 涓庡妯℃€佽瘉鎹寘

### 绾︽潫瀵硅薄

绾︽潫 `spec.md` 涓?P3 鐨勮祫鏂欏叆搴撱€乻ource_registry銆佸绱㈠紩妫€绱€乻cene_object_registry銆佸妯℃€佽瘉鎹拰 evidence_package銆?
### 鍏佽淇敼鐨勬枃浠惰寖鍥?
- `src/knowledge/**`
- `configs/rag.yaml`
- `scripts/ingest_sources.py`
- `data/raw_sources/**`
- `data/processed/**`
- `tests/integration/rag_pipeline/**`

### 绂佹淇敼鐨勬枃浠惰寖鍥?
- 涓嶅緱鍦?P3 鍐欏洖绛旂敓鎴愰€昏緫銆?- 涓嶅緱鍦ㄧ煡璇嗗簱涓啓鍏ョ敤鎴疯蹇嗐€佺敤鎴峰弽棣堟垨妯″瀷鑷敱鐢熸垚浜嬪疄銆?- 涓嶅緱鍦ㄦ绱㈡ā鍧椾慨鏀?Prompt 璧勪骇鐘舵€併€?
### 绂佹寮曞叆鐨勮璁″亸宸?
- 绂佹鍙仛鍚戦噺 top_k 妫€绱㈣€屾病鏈夊叧閿瘝銆佸厓鏁版嵁銆佹潵婧愬拰璐ㄩ噺闂ㄦ帶銆?- 绂佹鏈鏍歌祫鏂欎綔涓烘牳蹇冭埅绌轰簨瀹炪€?- 绂佹瑙嗚璇嗗埆鏍囩鏃犳枃鏈氦鍙夐獙璇佸氨浣滀负浜嬪疄渚濇嵁銆?- 绂佹 missing_evidence 涓虹┖浣嗗疄闄呰瘉鎹笉瓒炽€?
### 蹇呴』閬靛畧鐨勬帴鍙ｈ竟鐣?
- `source_registry.review_status=reviewed` 鎵嶈兘鎴愪负鏍稿績璇佹嵁銆?- `scene_object_registry` 鏄?3D 鍦烘櫙涓庣煡璇嗗疄浣撶殑缁戝畾鍏ュ彛銆?- `EvidenceGate` 鍙垽鏂瘉鎹寘鑳藉惁杩涘叆鐢熸垚锛屼笉璇勪环鏈€缁堢瓟妗堟槸鍚﹀繝瀹炰娇鐢ㄨ瘉鎹€?
### 蹇呴』閫氳繃鐨勬祴璇?
- 璧勬枡鍏ュ簱瀛楁瀹屾暣銆?- 绮剧‘鏈鑳借蛋鍏抽敭璇嶉€氶亾銆?- 鍙ｈ闂鑳借蛋鍚戦噺鎴栨贩鍚堥€氶亾銆?- 褰撳墠 selected_object_id 鑳界粦瀹氬満鏅璞°€?- draft/deprecated 鏉ユ簮琚帓闄や负鏍稿績璇佹嵁銆?
### 蹇呴』婊¤冻鐨勬棩蹇椼€佸紓甯搞€侀厤缃姹?
- 璁板綍 retrieval_plan銆乹uery_variants銆乧hannel_hits銆乬ate_status銆乵issing_evidence銆?- top_k銆侀槇鍊笺€佹潈濞佺瓑绾с€乺eview_status 杩囨护蹇呴』閰嶇疆鍖栥€?- 妫€绱㈠け璐ヨ杩斿洖缁撴瀯鍖栧師鍥狅紝涓嶈繑鍥炵┖瀛楃涓层€?
### 瀹夊叏杈圭晫

鐭ヨ瘑搴撳彧鎺ユ敹浜哄伐瀹℃牳璧勬枡銆侀」鐩祫鏂欍€丳DF/鍥炬枃瑙ｆ瀽鍜?3D 鍏冩暟鎹€傜敤鎴峰弽棣堝彧鑳戒綔涓鸿瑙ｆ垨寰呮牳鏌ヤ俊鍙凤紝涓嶈兘鎴愪负浜嬪疄搴撴潵婧愩€?
### 鍥炴粴绛栫暐

鍏ュ簱璧勬枡璐ㄩ噺鏈夐棶棰樻椂锛屽皢 source 鎴?chunk 鏍囪涓?`deprecated`锛岄噸寤虹储寮曪紱涓嶅緱鐩存帴闈欓粯鍒犻櫎閫犳垚璇佹嵁閾炬柇瑁傘€?
### 闃舵瀹屾垚鍓嶆鏌ユ竻鍗?
- [ ] `evidence_package` 鍖呭惈 query_understanding銆乻cene_binding銆乺etrieval_plan銆乪vidence_items銆乧laim_support_map銆乵issing_evidence銆乬eneration_boundary銆乤udit_trace銆?- [ ] 瑙嗚璇佹嵁鏈?source_id銆乥box 鎴?layout_trace銆?- [ ] `generation_boundary` 鏄庣‘绂佹鏃犺瘉鎹唴瀹广€?- [ ] rag 闆嗘垚娴嬭瘯閫氳繃銆?
## P4锛氬洖绛旂敓鎴愩€佹潵婧愮粦瀹氫笌缁撴瀯鍖栬緭鍑?
### 绾︽潫瀵硅薄

绾︽潫 `spec.md` 涓?P4 鐨?answer type 璺敱銆乪vidence_sketch銆乬rounded_drafting銆乻ource_binding銆乤nswer_envelope 鍜?display_blocks銆?
### 鍏佽淇敼鐨勬枃浠惰寖鍥?
- `src/generation/**`
- `tests/unit/generation/**`
- `tests/integration/answer_pipeline/**`
- `configs/app.yaml` 涓?generation 鐩稿叧閰嶇疆

### 绂佹淇敼鐨勬枃浠惰寖鍥?
- 涓嶅緱鍦?P4 淇敼鐭ヨ瘑搴撹瘉鎹€?- 涓嶅緱鍦?P4 鐩存帴鍐欓暱鏈熻蹇嗐€?- 涓嶅緱缁曡繃 P5 鐩存帴鎶?draft 鏍囪涓烘渶缁堝畨鍏ㄨ緭鍑恒€?
### 绂佹寮曞叆鐨勮璁″亸宸?
- 绂佹鐢熸垚鍣ㄥ嚟妯″瀷甯歌瘑琛ヨ埅绌哄弬鏁般€?- 绂佹涓轰簡琛ㄨ揪鐢熷姩鏀瑰彉浜嬪疄鍚箟銆?- 绂佹 source_binding 浜嬪悗闅忔剰璐存潵婧愩€?- 绂佹鎶婄敤鎴峰亸濂戒綔涓轰簨瀹炰緷鎹€?
### 蹇呴』閬靛畧鐨勬帴鍙ｈ竟鐣?
- `evidence_package` 鏄簨瀹炲敮涓€鏉ユ簮銆?- `memory_context` 鍙帶鍒惰〃杈炬柟寮忓拰绾犲亸鎻愰啋銆?- `answer_envelope` 蹇呴』鍖呭惈 claim_candidates锛屼緵 P5 妫€鏌ャ€?- 鍙傛暟浜嬪疄鍨嬪洖绛斿繀椤绘湁鏉冨▉鏉ユ簮鎴栬緭鍑轰笉纭畾璇存槑銆?
### 蹇呴』閫氳繃鐨勬祴璇?
- 缂哄皯鍏蜂綋鍙傛暟璇佹嵁鏃朵笉杈撳嚭鏁板€笺€?- 姣忎釜鍏抽敭 claim 鏈?evidence_id 鎴?uncertainty_notes銆?- 閮ㄤ欢鍦烘櫙鍥炵瓟浣跨敤 scene_state.component_id銆?- display_blocks 涓嶆敼鍙樹簨瀹炲唴瀹广€?
### 蹇呴』婊¤冻鐨勬棩蹇椼€佸紓甯搞€侀厤缃姹?
- generation_log 璁板綍 template_id銆乪vidence_ids銆乻ource_binding_count銆乽ncertainty_count銆乴atency_ms銆?- 杈撳嚭闀垮害銆佹祦寮忕瓥鐣ャ€佸畨鍏ㄦā鏉垮繀椤婚厤缃寲銆?- 缁撴瀯鍖栬緭鍑虹己瀛楁鏃跺け璐ラ噸璇曟垨闄嶇骇銆?
### 瀹夊叏杈圭晫

娑夊強缁翠慨銆佹敼瑁呫€侀琛屾搷浣溿€佹晠闅滃缃垨鍗遍櫓瀹為獙鏃讹紝鐢熸垚鍣ㄥ彧鑳借緭鍑虹鏅В閲婂拰瀹夊叏寤鸿锛屼笉鑳借緭鍑哄彲鎵ц姝ラ鎴栧弬鏁般€?
### 鍥炴粴绛栫暐

鐢熸垚妯℃澘瀵艰嚧骞昏鐜囧崌楂樻椂锛屽洖婊?Prompt 璧勪骇鎴?generation policy锛屽苟淇濈暀澶辫触鏍蜂緥杩涘叆璇勬祴闆嗐€?
### 闃舵瀹屾垚鍓嶆鏌ユ竻鍗?
- [ ] `answer_envelope` 瀛楁瀹屾暣銆?- [ ] `source_binding` 涓?evidence_id 瀵归綈銆?- [ ] unsupported claim 涓嶄細琚綋浣滄渶缁堜簨瀹炪€?- [ ] generation 鍗曟祴鍜屼富閾捐矾闆嗘垚娴嬭瘯閫氳繃銆?
## P5锛氳嚜鎴戞鏌ャ€佸姩浣滃垎娴佷笌鍥為€€闂幆

### 绾︽潫瀵硅薄

绾︽潫 `spec.md` 涓?P5 鐨?claim 鎶藉彇銆佽瘉鎹榻愩€佸満鏅?澶氭ā鎬?杈圭晫/瀹夊叏妫€鏌ャ€乻core_card 鍜?action_decision銆?
### 鍏佽淇敼鐨勬枃浠惰寖鍥?
- `src/self_check/**`
- `src/core/state_machine.py` 涓繀瑕佺殑鍥為€€杈规墿灞?- `tests/unit/self_check/**`
- `tests/integration/answer_pipeline/**`

### 绂佹淇敼鐨勬枃浠惰寖鍥?
- 涓嶅緱鍦?self_check 涓洿鎺ュ啓闀挎湡璁板繂銆?- 涓嶅緱鍦?self_check 涓洿鎺ヤ慨鏀圭煡璇嗗簱璧勬枡銆?- 涓嶅緱璁╄嚜妫€妯″潡鐢熸垚鍏ㄦ柊浜嬪疄绛旀銆?
### 绂佹寮曞叆鐨勮璁″亸宸?
- 绂佹鑷鍙繑鍥炩€滈€氳繃/涓嶉€氳繃鈥濊€屾病鏈?issue_list銆?- 绂佹 evidence_gate 涓庤嚜妫€閲嶅鍙洖閫昏緫娣峰湪涓€璧枫€?- 绂佹鏃犻檺 RETRIEVE_MORE 鎴?REWRITE_ONLY 寰幆銆?- 绂佹 LLM Judge 鍗曠嫭鍐冲畾鎵€鏈夐珮椋庨櫓瀹夊叏闂銆?
### 蹇呴』閬靛畧鐨勬帴鍙ｈ竟鐣?
- 鑷鍙鍐宠川閲忓拰鍔ㄤ綔锛屼笉鎵挎媴鍒濈鐢熸垚鑱岃矗銆?- `RETRIEVE_MORE` 蹇呴』鎼哄甫缂哄け璇佹嵁鍘熷洜銆?- `REWRITE_ONLY` 蹇呴』鎼哄甫 revised_instruction銆?- `SAFE_RESPONSE` 鐢卞畨鍏ㄦ鏌ョ‖瑙﹀彂銆?
### 蹇呴』閫氳繃鐨勬祴璇?
- 鏃犺瘉鎹弬鏁?claim 涓?PASS銆?- 鏈虹考鍦烘櫙鍥炵瓟鍙戝姩鏈哄唴瀹硅鎷︽埅銆?- Prompt/璁板繂瓒婃潈浣滀负浜嬪疄鏉ユ簮琚嫤鎴€?- 鍗遍櫓鎿嶄綔璇锋眰瑙﹀彂 SAFE_RESPONSE銆?- 瓒呰繃寰幆涓婇檺瑙﹀彂 STOP銆?
### 蹇呴』婊¤冻鐨勬棩蹇椼€佸紓甯搞€侀厤缃姹?
- 璁板綍 score_card銆乫ailed_checks銆乽nsupported_claims銆乤ction_decision銆乴oop_count銆?- 闃堝€煎繀椤婚厤缃寲銆?- check_report 缂哄瓧娈垫椂涓嶅緱闈欓粯閫氳繃銆?
### 瀹夊叏杈圭晫

鑷鏃ュ織涓嶅緱娉勯湶鏁忔劅涓汉淇℃伅銆傚璇煶鏉ユ簮鏂囨湰锛屽簲璁板綍蹇呰鎽樿鍜岀疆淇″害锛屼笉淇濆瓨鍘熷闊抽銆?
### 鍥炴粴绛栫暐

鑷瑙勫垯璇潃姝ｅ父绛旀鏃讹紝鍏堝鍔犳祴璇曟牱渚嬪拰闃堝€奸厤缃紝鍐嶈皟鏁磋鍒欙紱涓嶅緱鍒犻櫎瀹夊叏妫€鏌ャ€?
### 闃舵瀹屾垚鍓嶆鏌ユ竻鍗?
- [ ] check_report 鍖呭惈 score_card銆乮ssue_list銆乫ailed_checks銆乤ction_decision銆乺evised_instruction銆乤udit_log銆?- [ ] 姣忎釜鍥為€€鍔ㄤ綔閮芥湁 reason銆?- [ ] 鏈€澶у惊鐜鏁扮敓鏁堛€?- [ ] self_check 娴嬭瘯閫氳繃銆?
## P6锛氬弽棣堟敼鍐欍€佸杞?checkpoint 涓庤蹇嗗€欓€?
### 绾︽潫瀵硅薄

绾︽潫 `spec.md` 涓?P6 鐨?checkpoint銆佸弽棣堝垎绫汇€佽瘉鎹攣瀹氥€佸彈鎺ф敼鍐欍€乨elta_map銆佽蹇嗗€欓€夊拰鏀瑰啓鍚庤嚜妫€銆?
### 鍏佽淇敼鐨勬枃浠惰寖鍥?
- `src/feedback/**`
- `src/memory/**` 涓?memory_update_candidate 鎺ュ彛
- `tests/unit/feedback/**`
- `tests/integration/feedback_loop/**`

### 绂佹淇敼鐨勬枃浠惰寖鍥?
- 涓嶅緱鍦?feedback 妯″潡鐩存帴淇敼鐭ヨ瘑搴撲簨瀹炪€?- 涓嶅緱鍦?feedback 妯″潡鐩存帴鎶婂亸濂藉啓鎴?active 闀挎湡璁板繂銆?- 涓嶅緱璺宠繃 P5 鑷杈撳嚭鏀瑰啓缁撴灉銆?
### 绂佹寮曞叆鐨勮璁″亸宸?
- 绂佹鎶婂弽棣堟敼鍐欏綋浣滈噸鏂伴棶绛旓紝浠庤€屼涪澶变笂涓€杞瘉鎹€?- 绂佹鐢ㄦ埛璇粹€滀綘閿欎簡鈥濆氨鐩存帴鏀瑰彛銆?- 绂佹鏍煎紡杞崲鏃朵负浜嗚〃鏍煎畬鏁寸紪閫犺祫鏂欍€?- 绂佹鍒犻櫎蹇呰瀹夊叏鎻愮ず銆?
### 蹇呴』閬靛畧鐨勬帴鍙ｈ竟鐣?
- 鎵€鏈夋敼鍐欏熀浜?checkpoint 鎭㈠鐨?previous_answer_envelope 鍜?evidence_package銆?- `FACT_CHALLENGE` 蹇呴』鐢熸垚 verification_queries 鎴栧洖鍒?P3銆?- `memory_update_candidate` 蹇呴』浜ょ粰 P2 娌荤悊銆?- `delta_map` 蹇呴』璁板綍鏂板銆佸垹闄ゃ€佹敼鍐?claim銆?
### 蹇呴』閫氳繃鐨勬祴璇?
- SIMPLIFY 淇濈暀 source_binding銆?- SHORTEN 涓嶅垹闄ゅ畨鍏ㄦ彁閱掋€?- FORMAT_TRANSFORM 瀵规棤璇佹嵁缁村害杈撳嚭鈥滆祫鏂欐湭瑕嗙洊鈥濄€?- FACT_CHALLENGE 涓嶇洿鎺ヨ鐩栦簨瀹炪€?- SAFETY_SENSITIVE 寮哄埗 SAFE_RESPONSE銆?
### 蹇呴』婊¤冻鐨勬棩蹇椼€佸紓甯搞€侀厤缃姹?
- rewrite_log 璁板綍 feedback_intent銆乺ewrite_action銆乧hanged_claims銆乸reserved_source_binding銆?- max_rewrite_rounds 蹇呴』閰嶇疆鍖栧苟鐢熸晥銆?- checkpoint 缂哄け瑕佹槑纭檷绾с€?
### 瀹夊叏杈圭晫

鐢ㄦ埛鍙嶉鍙兘鍖呭惈閿欒浜嬪疄鎴栬秺鐣岃姹傘€傚弽棣堝彧鑳戒綔涓哄緟澶勭悊淇″彿锛屼笉鑳界洿鎺ユ垚涓轰簨瀹炪€丳rompt 璧勪骇鎴栭暱鏈熻蹇嗐€?
### 鍥炴粴绛栫暐

鏀瑰啓妯℃澘閫犳垚鏂板骞昏鏃讹紝鍥炴粴妯℃澘鐗堟湰锛屽皢澶辫触鍙嶉鍔犲叆璇勬祴闆嗭紝骞堕檷浣庡搴?Prompt 璧勪骇鐘舵€併€?
### 闃舵瀹屾垚鍓嶆鏌ユ竻鍗?
- [ ] checkpoint 淇濆瓨瀹屾暣涓婁竴杞瘉鎹拰鑷缁撴灉銆?- [ ] evidence_lock 闃叉鏂板鏃犺瘉鎹?claim銆?- [ ] 鏀瑰啓鍚庨噸鏂拌繘鍏?P5銆?- [ ] feedback 娴嬭瘯閫氳繃銆?
## P7锛氳闊充氦浜掍笌瀹炴椂浼氳瘽

### 绾︽潫瀵硅薄

绾︽潫 `spec.md` 涓?P7 鐨勯煶棰戜紶杈撱€乂AD銆丄SR銆佹湳璇籂閿欍€乿oice_query_normalizer銆乀TS銆乥arge_in 鍜岃闊虫寚鏍囥€?
### 鍏佽淇敼鐨勬枃浠惰寖鍥?
- `src/voice/**`
- `src/input/voice_query_normalizer.py`
- `configs/voice.yaml`
- `tests/unit/voice/**`
- `tests/integration/voice_loop/**`

### 绂佹淇敼鐨勬枃浠惰寖鍥?
- 涓嶅緱璁?voice 妯″潡缁曡繃 P1/P3/P4/P5 涓婚摼璺洿鎺ュ洖绛旇埅绌轰簨瀹炪€?- 涓嶅緱鍦?voice 妯″潡鐩存帴鍐欓暱鏈熻蹇嗐€?- 涓嶅緱鎶婁綆缃俊 ASR 鏂囨湰閫佸叆 RAG 姝ｅ紡妫€绱€?
### 绂佹寮曞叆鐨勮璁″亸宸?
- 绂佹绔埌绔?speech-to-speech 妯″瀷浣滀负浜嬪疄闂瓟涓婚摼璺€?- 绂佹 TTS 鎾姤闀跨瘒涔﹂潰绛旀銆?- 绂佹 barge_in 鍙褰曚笉鍋滄鏃ч煶棰戙€?- 绂佹鍘熷闊抽榛樿鎸佷箙鍖栥€?
### 蹇呴』閬靛畧鐨勬帴鍙ｈ竟鐣?
- `ASRProvider`銆乣TTSProvider` 蹇呴』鍙浛鎹€?- `voice_query_object` 蹇呴』鍖呭惈 raw_transcript銆乧orrected_transcript銆乶ormalized_query銆乤sr_confidence銆乮ntent_type銆乼arget_entity銆乻cene_object_id銆乶eeds_clarification銆?- spoken_answer 鏉ヨ嚜宸查€氳繃涓婚摼璺殑 answer_envelope銆?- barge_in 浜嬩欢杩涘叆 P6 鍙嶉鏀瑰啓銆?
### 蹇呴』閫氳繃鐨勬祴璇?
- 浣庣疆淇?ASR 瑙﹀彂婢勬竻銆?- 鑸┖鏈璇瘑鍒Е鍙戠籂閿欐垨纭銆?- TTS 鎾姤涓墦鏂繘鍏?INTERRUPTED/REWRITE銆?- spoken_answer 闀垮害鍙楁帶銆?- 鍘熷闊抽涓嶉粯璁よ惤鐩樸€?
### 蹇呴』婊¤冻鐨勬棩蹇椼€佸紓甯搞€侀厤缃姹?
- 璁板綍 ASR 缃俊搴︺€乂AD 鐘舵€併€乀TS 鐘舵€併€乥arge_in 鏃堕棿銆佽闊虫寚鏍囥€?- Provider銆侀槇鍊笺€佹湳璇瘝鍏歌矾寰勩€佹渶澶ц闊抽暱搴﹀繀椤婚厤缃寲銆?- 闊抽閿欒涓嶅簲閲嶈窇 RAG锛屼繚鐣欐枃鏈洖绛旈檷绾с€?
### 瀹夊叏杈圭晫

璇煶杈撳叆鏇村鏄撹璇嗗埆锛屽洜姝や簨瀹為摼璺繀椤绘洿淇濆畧銆備綆缃俊銆佹棤鍦烘櫙瀵硅薄銆佹寚浠ｄ笉鏄庢椂鍏堟緞娓咃紝涓嶅己绛斻€?
### 鍥炴粴绛栫暐

璇煶 Provider 璐ㄩ噺涓嶇ǔ瀹氭椂鍒囨崲鍒板鐢?Provider 鎴栨枃鏈ā寮忥紝淇濈暀涓婚摼璺彲鐢ㄦ€с€?
### 闃舵瀹屾垚鍓嶆鏌ユ竻鍗?
- [ ] 璇煶鐘舵€佹満鍖呭惈 IDLE銆丩ISTENING銆乀RANSCRIBING銆乁NDERSTANDING銆丷ETRIEVING銆丟ENERATING銆丼PEAKING銆両NTERRUPTED銆丷EWRITE銆丆LARIFY銆?- [ ] 浣庣疆淇?ASR 涓嶈繘鍏ユ寮忔绱€?- [ ] barge_in 浼樺厛绾ф渶楂樸€?- [ ] voice 娴嬭瘯閫氳繃銆?
## P8锛氭棩蹇楄瘎娴嬨€佹紨绀哄璁′笌閮ㄧ讲娌荤悊

### 绾︽潫瀵硅薄

绾︽潫 `spec.md` 涓?P8 鐨?run_trace 姹囨€汇€佽瘎娴嬮泦銆佹寚鏍囥€佹紨绀烘姤鍛娿€侀儴缃查厤缃拰鍥炲綊娴嬭瘯銆?
### 鍏佽淇敼鐨勬枃浠惰寖鍥?
- `configs/evals.yaml`
- `scripts/**`
- `docs/**`
- `tests/e2e/**`
- `tests/integration/**` 涓瘎娴嬬浉鍏虫枃浠?
### 绂佹淇敼鐨勬枃浠惰寖鍥?
- 涓嶅緱涓轰簡璇勬祴閫氳繃淇敼涓氬姟閫昏緫缁曡繃鐪熷疄娴佺▼銆?- 涓嶅緱鍦ㄦ紨绀鸿剼鏈腑浼€?evidence_package 鎴?check_report銆?- 涓嶅緱瀵煎嚭鏈劚鏁忛殣绉併€佸師濮嬭闊虫垨鏈巿鏉冨涔犺建杩广€?
### 绂佹寮曞叆鐨勮璁″亸宸?
- 绂佹鍙祴鏈€缁堝洖绛旀枃鏈紝涓嶆祴璇佹嵁閾俱€?- 绂佹鍙祴姝ｅ父璺緞锛屼笉娴嬭瘉鎹笉瓒炽€佸満鏅敊浣嶃€佸畨鍏ㄥ拰璇煶浣庣疆淇°€?- 绂佹閮ㄧ讲閰嶇疆渚濊禆寮€鍙戞満缁濆璺緞銆?
### 蹇呴』閬靛畧鐨勬帴鍙ｈ竟鐣?
- 璇勬祴鑴氭湰蹇呴』閫氳繃姝ｅ紡 API 鎴栨寮?pipeline 璋冪敤绯荤粺銆?- `run_trace` 蹇呴』鑳藉叧鑱?query銆乻cene銆乸rompt銆乵emory銆乺etrieval銆乤nswer銆乧heck銆乺ewrite銆乿oice銆?- 婕旂ず鎶ュ憡鍙兘灞曠ず鑴辨晱鏁版嵁銆?
### 蹇呴』閫氳繃鐨勬祴璇?
- 鏂囨湰闂瓟 E2E銆?- 鍙嶉鏀瑰啓 E2E銆?- 璇煶鐘舵€佹満 E2E 鎴栨ā鎷?E2E銆?- 璇佹嵁涓嶈冻鍜屽畨鍏ㄨ竟鐣屽洖褰掓祴璇曘€?- trace 瀵煎嚭娴嬭瘯銆?
### 蹇呴』婊¤冻鐨勬棩蹇椼€佸紓甯搞€侀厤缃姹?
- run_trace 缂哄叧閿瓧娈靛垯璇勬祴澶辫触銆?- 璇勬祴闃堝€笺€佸浠跺悕绉般€侀儴缃插彉閲忓繀椤婚厤缃寲銆?- 璇勬祴澶辫触鎶ュ憡蹇呴』瀹氫綅妯″潡鍜屽師鍥犮€?
### 瀹夊叏杈圭晫

婕旂ず涓庤瘎娴嬫暟鎹繀椤诲彲鑴辨晱銆佸彲澶嶇幇銆佸彲娓呯悊銆備笉寰楀湪鎶ュ憡涓毚闇茬湡瀹炵敤鎴烽殣绉佹垨瀵嗛挜銆?
### 鍥炴粴绛栫暐

閮ㄧ讲澶辫触鏃跺洖婊氬埌涓婁竴濂楅€氳繃璇勬祴鐨勯厤缃拰妯″瀷 Provider銆傝瘎娴嬮€€鍖栨椂淇濈暀澶辫触 run_trace锛屽洖婊氱浉鍏抽樁娈垫敼鍔ㄣ€?
### 闃舵瀹屾垚鍓嶆鏌ユ竻鍗?
- [ ] `run_eval.py --suite smoke` 鍙繍琛屻€?- [ ] E2E 瑕嗙洊鏂囨湰銆佸弽棣堛€佽闊虫牳蹇冭矾寰勩€?- [ ] trace 鎶ュ憡鑳借В閲婅瘉鎹懡涓€佽嚜妫€鍔ㄤ綔鍜屾渶缁堣緭鍑恒€?- [ ] 閮ㄧ讲閰嶇疆涓嶅惈鏈満缁濆璺緞鍜屽瘑閽ャ€?- [ ] P0 鍒?P8 鍏ㄩ儴娴嬭瘯鎴栨浛浠ｉ獙鏀惰褰曞凡閫氳繃銆?
```

## docs/STATUS.md

```
# STATUS.md

本文件由 Codex 在全自动开发过程中持续更新。

## 当前状态

P0 到 P6 已完成。P7/P8 未纳入本次用户目标，暂未开始。

## 阶段：P0

### 完成时间
2026-07-08 00:00 Asia/Shanghai

### 阶段目标
建立系统开发的共同地基，使后续 Prompt、记忆、RAG、生成、自检、反馈和语音模块都围绕同一套状态机、动作枚举、数据契约、配置和日志规范开发。

### 允许修改范围
- `pyproject.toml`
- `.env.example`
- `configs/app.yaml`
- `configs/providers.yaml`
- `src/core/**`
- `tests/unit/core/**`
- `README.md`

### 禁止修改范围
- P1 到 P8 尚未创建的业务模块不得塞入 P0。
- 不得修改原始 Word 文档和用户提供资料。
- 不得将演示数据、Prompt 模板、航空知识库内容写入 `src/core/`。

### 已完成内容
- 建立 Python 工程骨架、pytest 配置、README 和基础配置样例。
- 新增统一 `ActionDecision`、`AgentState`、状态跳转记录和回退链路。
- 新增基础数据契约：`scene_state`、`prompt_asset`、`memory_context`、`evidence_package`、`answer_envelope`、`check_report`、`rewrite_plan`、`voice_turn_event`、`feedback_event`、`run_trace`。
- 新增配置加载、核心异常和运行追踪辅助函数。
- 按用户追加条件，将运行环境调整为 `D:\APP\Python 3.13\Internet\.venv`，并将 `pyproject.toml` 的 `requires-python` 调整为 `>=3.11`。

### 修改文件
- 无

### 新增文件
- `pyproject.toml`
- `.env.example`
- `README.md`
- `configs/app.yaml`
- `configs/providers.yaml`
- `src/__init__.py`
- `src/core/__init__.py`
- `src/core/actions.py`
- `src/core/contracts.py`
- `src/core/errors.py`
- `src/core/settings.py`
- `src/core/state_machine.py`
- `src/core/tracing.py`
- `tests/unit/core/test_contracts.py`
- `tests/unit/core/test_state_machine.py`

### 删除文件
- 无

### 测试命令
```bash
D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe -m pytest tests\unit\core
```

### 测试结果
通过，`10 passed in 0.20s`。

### 实际调用过的关键 skill
- `using-superpowers`
- `brainstorming`（受用户全自动执行指令约束，未设置人工确认门）
- `writing-plans`（以 `docs/spec.md` 作为已有实施计划执行，未新增计划文件以避免越过 P0 harness）
- `verification-before-completion`

### 是否违反 harness.md
否。

### 未完成事项
- 技术栈、数据库、消息队列、前端通信协议仍按文档标记为待确认。

### 下一阶段是否可以开始
是。

## 阶段：P1

### 完成时间
2026-07-08 00:00 Asia/Shanghai

### 阶段目标
把用户文本问题、3D 场景状态和初步任务意图转成标准 `query_object`，并实现 Prompt 资产的路由、变量填充、注入顺序和风险边界。

### 允许修改范围
- `src/input/**`
- `src/prompts/**`
- `configs/prompts.yaml`
- `tests/unit/prompts/**`
- `tests/unit/core/test_contracts.py`

### 禁止修改范围
- 不得在 P1 修改 `src/knowledge/**` 以绕过检索。
- 不得在 P1 写入长期记忆。
- 不得在 Prompt 模块中写航空事实库内容。

### 已完成内容
- 新增 `QueryObject`、`PromptRunInput` 和场景绑定结果对象。
- 实现文本意图识别、飞机/部件/概念/反馈意图的轻量抽取。
- 实现场景指代绑定：明确选中对象直接绑定，多候选返回 `needs_clarification`。
- 新增 Prompt 资产模型、资产存储、路由器和注入摘要组装器。
- 配置化 Prompt 必填变量和注入顺序，并保证 `rag_evidence` 位于 Prompt 资产之前。
- 缺失 `rag_evidence` 时降级到基础模板，并标记不是事实型最终 Prompt。

### 修改文件
- 无

### 新增文件
- `configs/prompts.yaml`
- `src/input/__init__.py`
- `src/input/query_object.py`
- `src/input/query_understanding.py`
- `src/input/scene_binding.py`
- `src/prompts/__init__.py`
- `src/prompts/asset_models.py`
- `src/prompts/asset_store.py`
- `src/prompts/router.py`
- `src/prompts/assembler.py`
- `tests/unit/prompts/test_prompt_router.py`
- `tests/unit/prompts/test_prompt_assembler.py`

### 删除文件
- 无

### 测试命令
```bash
D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe -m pytest tests\unit\core tests\unit\prompts
```

### 测试结果
通过，`17 passed in 0.20s`。

### 实际调用过的关键 skill
- `using-superpowers`
- `writing-plans`
- `verification-before-completion`

### 是否违反 harness.md
否。

### 未完成事项
- 首批 Prompt 模板内容和人工审核流程仍为待确认；当前仅实现非事实性的安全边界模板与路由机制。

### 下一阶段是否可以开始
是。

## 阶段：P2

### 完成时间
2026-07-08 00:00 Asia/Shanghai

### 阶段目标
建立事件日志、热状态、学习者画像、候选记忆、时间衰减和审计机制，让记忆只用于个性化、指代和教学路径，不污染航空事实。

### 允许修改范围
- `src/memory/**`
- `configs/memory.yaml`
- `tests/unit/memory/**`
- `tests/integration/answer_pipeline/**` 中与 `memory_context` 相关的测试

### 禁止修改范围
- 不得修改 `src/knowledge/source_registry.py` 或知识库事实表来写入用户说法。
- 不得在记忆模块中生成最终回答。
- 不得让任意 Agent 直接写长期记忆而不走 `MemoryController`。

### 已完成内容
- 新增事件日志、候选记忆、结构化记忆、记忆状态机和写入结果对象。
- 实现 `MemoryController` 作为唯一长期写入入口。
- 实现事件先行流程：`event_log -> candidate -> temporal normalize -> governance -> store`。
- 实现低置信、高隐私、未登记事件和用户航空事实候选的拒绝治理。
- 实现偏好冲突的 `supersedes/superseded_by` 关系。
- 实现过期/陈旧状态刷新、记忆审计和 `memory_context` 构造。

### 修改文件
- 无

### 新增文件
- `configs/memory.yaml`
- `src/memory/__init__.py`
- `src/memory/audit.py`
- `src/memory/controller.py`
- `src/memory/event_log.py`
- `src/memory/maintenance_jobs.py`
- `src/memory/schemas.py`
- `src/memory/stores.py`
- `src/memory/temporal.py`
- `tests/unit/memory/test_memory_context.py`
- `tests/unit/memory/test_memory_write_flow.py`
- `tests/unit/memory/test_temporal_status.py`

### 删除文件
- 无

### 测试命令
```bash
D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe -m pytest tests\unit\core tests\unit\prompts tests\unit\memory
```

### 测试结果
通过，`25 passed in 0.18s`。

### 实际调用过的关键 skill
- `using-superpowers`
- `writing-plans`
- `verification-before-completion`

### 是否违反 harness.md
否。

### 未完成事项
- 真实用户授权 UI、删除流程、原始语音保留策略仍为待确认。
- 当前存储为内存实现，生产持久化方案待确认。

### 下一阶段是否可以开始
是。

## 阶段：P3

### 完成时间
2026-07-08 00:00 Asia/Shanghai

### 阶段目标
建立“事实由知识检索提供”的核心能力，将文本、场景对象和多模态线索统一入库、检索、门控，并输出可审计的 `evidence_package`。

### 允许修改范围
- `src/knowledge/**`
- `configs/rag.yaml`
- `scripts/ingest_sources.py`
- `data/raw_sources/**`
- `data/processed/**`
- `tests/integration/rag_pipeline/**`

### 禁止修改范围
- 不得在 P3 写回答生成逻辑。
- 不得在知识库中写入用户记忆、用户反馈或模型自由生成事实。
- 不得在检索模块修改 Prompt 资产状态。

### 已完成内容
- 新增 `source_registry`、`scene_object_registry`、文本/PDF/视觉/场景入库适配对象。
- 新增关键词索引、轻量语义索引、混合 RRF 召回、视觉页索引和轻量图索引。
- 实现 `RetrievalController`、`RetrievalPlan`、`EvidencePackageBuilder` 和 `EvidenceGate`。
- 强制仅 `reviewed` 来源可作为核心航空事实证据。
- 场景对象可通过 `selected_object_id` 绑定并过滤部件证据。
- 未审核资料和无文本交叉校验视觉线索不能作为核心事实证据。
- 新增演示入库脚本 `scripts/ingest_sources.py`，并在最终验收中补充直接运行路径初始化。

### 修改文件
- `scripts/ingest_sources.py`

### 新增文件
- `configs/rag.yaml`
- `data/raw_sources/.gitkeep`
- `data/processed/.gitkeep`
- `src/knowledge/__init__.py`
- `src/knowledge/evidence_gate.py`
- `src/knowledge/evidence_package.py`
- `src/knowledge/retrieval_controller.py`
- `src/knowledge/scene_object_registry.py`
- `src/knowledge/schemas.py`
- `src/knowledge/source_registry.py`
- `src/knowledge/ingestion/__init__.py`
- `src/knowledge/ingestion/pdf_ingestor.py`
- `src/knowledge/ingestion/scene_ingestor.py`
- `src/knowledge/ingestion/text_ingestor.py`
- `src/knowledge/ingestion/visual_ingestor.py`
- `src/knowledge/indexes/__init__.py`
- `src/knowledge/indexes/graph_index.py`
- `src/knowledge/indexes/hybrid_index.py`
- `src/knowledge/indexes/keyword_index.py`
- `src/knowledge/indexes/vector_index.py`
- `src/knowledge/indexes/visual_page_index.py`
- `scripts/ingest_sources.py`
- `tests/integration/rag_pipeline/test_basic_retrieval.py`
- `tests/integration/rag_pipeline/test_scene_binding_retrieval.py`

### 删除文件
- 无

### 测试命令
```bash
D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe -m pytest tests\unit\core tests\unit\prompts tests\unit\memory tests\integration\rag_pipeline
```

### 测试结果
通过，`31 passed in 0.41s`。

### 实际调用过的关键 skill
- `using-superpowers`
- `writing-plans`
- `verification-before-completion`

### 是否违反 harness.md
否。

### 未完成事项
- 最终向量库、图数据库、PDF 解析工具和 embedding 模型仍为待确认。
- 当前 RAG 为本地内存 MVP，接口保持可替换。

### 下一阶段是否可以开始
是。

## 阶段：P4

### 完成时间
2026-07-08 00:00 Asia/Shanghai

### 阶段目标
基于 `evidence_package`、`scene_state`、`memory_context` 和 Prompt 路由结果生成可检查、可展示、可追溯的 `answer_envelope`，实现来源绑定和结构化展示块。

### 允许修改范围
- `src/generation/**`
- `tests/unit/generation/**`
- `tests/integration/answer_pipeline/**`
- `configs/app.yaml` 中 generation 相关配置

### 禁止修改范围
- 不得在 P4 修改知识库证据。
- 不得在 P4 直接写长期记忆。
- 不得绕过 P5 直接把 draft 标记为最终安全输出。

### 已完成内容
- 新增回答类型路由、证据草图、生成计划、非流式 grounded generator、来源绑定和展示块构建。
- 参数证据不足时不输出具体数值。
- 每个关键 claim 有 `evidence_id` 或不确定性说明。
- 个性化只改变表达前缀，不改变事实文本。

### 修改文件
- `tests/integration/answer_pipeline/test_grounded_answer.py`

### 新增文件
- `src/generation/__init__.py`
- `src/generation/answer_types.py`
- `src/generation/citation_binding.py`
- `src/generation/display_blocks.py`
- `src/generation/evidence_sketch.py`
- `src/generation/generator.py`
- `src/generation/planner.py`
- `tests/unit/generation/test_answer_type_router.py`
- `tests/unit/generation/test_citation_binding.py`
- `tests/integration/answer_pipeline/test_grounded_answer.py`

### 删除文件
- 无

### 测试命令
```bash
D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe -m pytest tests\unit tests\integration\rag_pipeline tests\integration\answer_pipeline
```

### 测试结果
通过，`40 passed in 0.32s`。

### 失败修复记录
- 首次运行出现 1 个断言失败：测试用例“解释一下机翼升力”在 P1 中会被识别为 `component_scene`，但 P4 测试期待 `concept_explanation`。
- 根因是 P4 测试夹具与已验收的 P1 意图规则不一致。
- 修复方式：将该 P4 概念解释测试输入改为“解释一下升力”，未修改 P1 已验收逻辑。

### 实际调用过的关键 skill
- `using-superpowers`
- `writing-plans`
- `systematic-debugging`
- `verification-before-completion`

### 是否违反 harness.md
否。

### 未完成事项
- 模型 Provider、结构化输出约束方式和前端 `display_blocks` 渲染协议仍为待确认。
- 当前生成器为规则化 MVP，后续接入模型时仍必须保持证据绑定和 P5 自检边界。

### 下一阶段是否可以开始
是。

## 阶段：P5

### 完成时间
2026-07-08 00:00 Asia/Shanghai

### 阶段目标
建立独立质量闸门，对回答做事实主张、证据对齐、场景一致性、多模态一致性、记忆/Prompt 边界和安全检查，并通过 `action_decision` 驱动回退。

### 允许修改范围
- `src/self_check/**`
- `src/core/state_machine.py` 中必要的回退边扩展
- `tests/unit/self_check/**`
- `tests/integration/answer_pipeline/**`

### 禁止修改范围
- 不得在 self_check 中直接写长期记忆。
- 不得在 self_check 中直接修改知识库资料。
- 不得让自检模块生成全新事实答案。

### 已完成内容
- 新增 claim 抽取、证据对齐、场景检查、多模态检查、边界/安全检查、评分卡和动作路由。
- `CheckReport` 输出 `score_card`、`issue_list`、`failed_checks`、`action_decision`、`revised_instruction` 和 `audit_log`。
- 高风险无证据 claim 路由到 `RETRIEVE_MORE`。
- Prompt/记忆越权作为事实来源路由到 `REWRITE_ONLY`。
- 场景对象错位路由到 `ASK_CLARIFICATION`。
- 危险操作细节路由到 `SAFE_RESPONSE`。
- 循环超过上限路由到 `STOP`。

### 修改文件
- `tests/unit/self_check/test_decision_router.py`

### 新增文件
- `src/self_check/__init__.py`
- `src/self_check/boundary_checker.py`
- `src/self_check/claim_extractor.py`
- `src/self_check/decision_router.py`
- `src/self_check/evidence_alignment.py`
- `src/self_check/multimodal_checker.py`
- `src/self_check/scene_checker.py`
- `src/self_check/score_card.py`
- `tests/unit/self_check/test_claim_support.py`
- `tests/unit/self_check/test_decision_router.py`
- `tests/integration/answer_pipeline/test_self_check_loop.py`

### 删除文件
- 无

### 测试命令
```bash
D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe -m pytest tests\unit tests\integration\rag_pipeline tests\integration\answer_pipeline
```

### 测试结果
通过，`48 passed in 0.31s`。

### 失败修复记录
- 首次运行出现 1 个断言失败：测试依赖 `issue_list[0]` 的顺序，但服务按“证据对齐 -> 边界检查”的顺序追加 issue。
- 根因是测试断言过度依赖列表顺序，动作分流已正确为 `REWRITE_ONLY`。
- 修复方式：改为顺序无关地检查 `issue_list` 中存在 `non_evidence_source_used_as_fact`。

### 实际调用过的关键 skill
- `using-superpowers`
- `writing-plans`
- `systematic-debugging`
- `verification-before-completion`

### 是否违反 harness.md
否。

### 未完成事项
- 人工复核入口、正式评分阈值来源和 LLM Judge 比例仍为待确认。
- 当前阈值通过 `DecisionPolicy` 可注入，后续可接入配置文件。

### 下一阶段是否可以开始
是。

## 阶段：P6

### 完成时间
2026-07-08 00:00 Asia/Shanghai

### 阶段目标
处理用户对上一轮回答的反馈，在证据锁定、来源保持和自检复核下做局部改写，并生成候选记忆。

### 允许修改范围
- `src/feedback/**`
- `src/memory/**` 中 `memory_update_candidate` 接口
- `tests/unit/feedback/**`
- `tests/integration/feedback_loop/**`

### 禁止修改范围
- 不得在 feedback 模块直接修改知识库事实。
- 不得在 feedback 模块直接把偏好写成 active 长期记忆。
- 不得跳过 P5 自检输出改写结果。

### 已完成内容
- 新增反馈意图解析、checkpoint 保存、证据锁定、rewrite planner、受控 rewriter 和 delta_map。
- 表达类反馈保留 `source_binding`；表格转换对无证据维度标记“资料未覆盖”。
- `FACT_CHALLENGE` 生成核查查询和不确定性说明，不直接覆盖事实。
- 安全敏感反馈输出 `SAFE_RESPONSE`，不保留可执行危险步骤。
- 稳定偏好只生成 `memory_update_candidate`，交回 P2 治理。

### 修改文件
- 无

### 新增文件
- `src/feedback/__init__.py`
- `src/feedback/checkpoint_store.py`
- `src/feedback/delta_map.py`
- `src/feedback/evidence_lock.py`
- `src/feedback/feedback_event.py`
- `src/feedback/parser.py`
- `src/feedback/rewrite_planner.py`
- `src/feedback/rewriter.py`
- `tests/unit/feedback/test_evidence_lock.py`
- `tests/unit/feedback/test_feedback_parser.py`
- `tests/integration/feedback_loop/test_rewrite_preserves_sources.py`

### 删除文件
- 无

### 测试命令
```bash
D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe -m pytest tests\unit tests\integration
```

### 测试结果
通过，`57 passed in 0.26s`。

### 实际调用过的关键 skill
- `using-superpowers`
- `writing-plans`
- `verification-before-completion`

### 是否违反 harness.md
否。

### 未完成事项
- 前端是否展示 `delta_map`、最大改写轮数的正式产品值仍为待确认。
- 当前 checkpoint 为内存实现，生产持久化策略待确认。

### 下一阶段是否可以开始
本轮用户目标到 P6 结束；P7/P8 未纳入本次目标，暂不开始。

## 最终验收记录：P0-P6

### 验收时间
2026-07-08 00:00 Asia/Shanghai

### 指定运行环境
- `D:\APP\Python 3.13\Internet\.venv`
- Python 3.11.9
- pytest 9.0.3

### 验收命令
```bash
D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe -m pytest
D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe -m compileall -q src scripts
D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe scripts\ingest_sources.py
```

### 验收结果
- 全量测试通过：`57 passed in 0.22s`。
- 编译检查通过：`compileall` 退出码为 0。
- 入库脚本启动检查通过：输出 `registered_sources=1 chunks=1`。

### 阶段范围确认
- P0 到 P6 均已有阶段记录。
- 系统工程目录按 `docs/spec.md` 的 P0-P6 范围建立。
- 功能范围限于 `docs/task.md` 的 P0-P6，未提前实现 P7/P8。
- 代码边界未绕过 `docs/harness.md`。

### 剩余待确认
- P7/P8 未执行，因为本轮用户目标明确到 P6。
- 真实 Provider、数据库、前端协议、持久化、人工复核入口和正式部署策略仍按阶段文档保留为待确认。

## 阶段：P0+

### 完成时间
2026-07-09 Asia/Shanghai

### 阶段目标
固化当前 MVP 与 plus 工业级升级目标之间的真实差距，建立 P1+ 到 P9+ 的审计基线和 baseline 测试记录。

### 完成内容
- 完整阅读 `docs/task_plus.md`、`docs/spec_plus.md`、`docs/harness_plus.md`、原始三件套、`docs/AUTO_DEV.md`、`docs/STATUS.md` 和 `AGENTS.md`。
- 扫描当前真实目录结构、配置、脚本、测试和文档。
- 新增 `docs/upgrade_audit.md`，记录 MVP 状态、模块差距、Mock/Offline 策略、命名一致性和后续阶段风险。
- 核验未发现 `deepseek-flash 2`，确认 `providers.yaml` 仍为 `pending_confirmation`。
- 明确 `SimpleVectorIndex` 是 token similarity fallback，`GroundedAnswerGenerator` 是规则化 fallback，`VoiceTurnEvent` 只是语音契约。
- 运行 baseline 全量 pytest。

### 修改文件
- `docs/STATUS.md`

### 新增文件
- `docs/upgrade_audit.md`

### 删除文件
- 无

### 测试命令
```powershell
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m pytest
```

### 测试结果
通过，`57 passed in 0.31s`。

### harness_plus.md 边界检查

* 是否跳阶段：否
* 是否提前实现后续阶段：否
* 是否污染全局 Python 环境：否
* 是否硬编码密钥或模型配置：否
* 是否删除测试规避失败：否
* 是否把 MVP 占位描述为工业级能力：否
* 是否把 Mock/Offline 验收描述为真实生产验收：否
* 是否引入未确认大型依赖：否
* 是否访问真实外部服务：否

### Mock / Offline 状态

* 是否使用 MockModelClient：不涉及
* 是否使用 Mock ASR/TTS：不涉及
* 是否使用 mock embedding：不涉及
* 是否使用 mock visual adapter：不涉及
* 真实 Provider 是否待后续接入：是

### 实际调用过的关键 skill
- `using-superpowers`
- `brainstorming`（以既有 plus 文档作为已确认设计，不设置额外人工确认门）
- `writing-plans`（以 `docs/spec_plus.md` 作为阶段级实施计划来源）
- `verification-before-completion`
- `browser:control-in-app-browser`（已按文档读取；P0+ 不涉及浏览器验证）

### 风险与待确认
- DeepSeek 真实 `model_id`、base_url、结构化输出能力待 P3+ 配置核验。
- CLI / HTTP API 优先级待 P2+ 按 CLI-first、HTTP optional 处理。
- 向量库、OCR/PDF layout、语音 Provider、评测框架均存在 Python 3.13 + Windows 兼容性风险。
- 父级 Git 仓库包含大量与本项目无关的变更，P0+ 未处理这些外部状态。

### 是否可以进入下一阶段
可以。

## 阶段：P9+

### 完成时间
2026-07-09 12:57:51 Asia/Shanghai

### 阶段目标
完成 P0+ 到 P9+ 的全链路工业级验收与演示交付，明确区分 Mock/Offline 验收和真实 Provider / 生产环境验收。

### 完成内容
- 重新读取目标文件 `C:\Users\SONGQI\.codex\attachments\da81fb58-5beb-425e-97e9-da1e4ad9be27\goal-objective.md`，确认本轮目标为 P0+ 到 P9+ 全自动顺序升级，真实 Provider 缺失不是阻塞条件。
- 核对 `docs/task_plus.md`、`docs/spec_plus.md`、`docs/harness_plus.md` 中 P9+ 要求，确认 P9+ 只做交付验收与文档，不修改业务逻辑。
- 运行 P9+ 必需验收命令：全量 pytest、compileall、smoke eval、trace export、deployment validate。
- 额外验证 CLI query smoke 与 `AppPipeline.health_check()`，确认应用入口和 service facade 可运行。
- 新增 `docs/release_acceptance.md`，汇总验收结论、证据、风险清单和真实 Provider 待接入项。
- 新增 `docs/demo_audit_report.md`，记录可复现演示命令、演示场景、trace 审计和演示边界。
- 更新 `README.md`，补充 P9+ 验收与演示审计入口。
- 明确最终结论：已达到工业级工程架构与 Mock/Offline 全链路验收标准；真实 Provider 接入与生产环境验收待后续材料补齐后执行。

### 修改文件
- `README.md`
- `docs/STATUS.md`
- `docs/trace_reports/p9_trace_acceptance.md`（trace export 验证产物）

### 新增文件
- `docs/release_acceptance.md`
- `docs/demo_audit_report.md`

### 删除文件
- 无

### 测试命令
```powershell
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m pytest
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m compileall -q src scripts
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" scripts\run_eval.py --suite smoke
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" scripts\export_trace_report.py --run-id p9_trace_acceptance
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" scripts\validate_deployment.py
$env:PYTHONPATH='src'; $env:PYTHONIOENCODING='utf-8'; & "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m app.cli --query "解释一下升力" --run-id "p9_cli_smoke_final" --no-trace
$env:PYTHONPATH='src'; $env:PYTHONIOENCODING='utf-8'; & "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -c "import json; from services.app_pipeline import AppPipeline; print(json.dumps(AppPipeline().health_check().to_dict(), ensure_ascii=False))"
```

### 测试结果

通过。

- 全量 pytest：`99 passed in 1.95s`，退出码 0。
- compileall：`COMPILEALL=ok`。
- smoke eval：`case_count=3`，`passed_count=3`，`pass_rate=1.0`，`mock_offline=true`，退出码 0。
- trace export：生成 `docs/trace_reports/p9_trace_acceptance.md`，`mock_offline=true`，退出码 0。
- deployment validate：`status=ok`，`missing_files=[]`，`provider_profile=mock`，`real_provider_pending=true`，退出码 0。
- CLI query smoke：退出码 0；无资料时返回 `clarification`，不伪造证据。
- `AppPipeline.health_check()`：返回 `{"status":"ok","entry_mode":"cli","trace_enabled":true}`，退出码 0。

### 补充探测记录
- 曾尝试 `python -m app.cli --health`，返回退出码 2。系统性排查后确认根因是当前 CLI 契约没有 `--health` 参数，health check 位于 `AppPipeline.health_check()`；P9+ 禁止临时修改业务逻辑，因此未新增 CLI 参数，改用契约内 `--query` 和 service facade health 验证入口。

### harness_plus.md 边界检查

* 是否跳阶段：否
* 是否提前实现后续阶段：否
* 是否污染全局 Python 环境：否
* 是否硬编码密钥或模型配置：否
* 是否删除测试规避失败：否
* 是否把 MVP 占位描述为工业级能力：否
* 是否把 Mock/Offline 验收描述为真实生产验收：否
* 是否引入未确认大型依赖：否
* 是否访问真实外部服务：否

### Mock / Offline 状态

* 是否使用 MockModelClient：是
* 是否使用 Mock ASR/TTS：是
* 是否使用 mock embedding：是
* 是否使用 mock visual adapter：是
* 真实 Provider 是否待后续接入：是

### 实际调用过的关键 skill
- `using-superpowers`
- `writing-plans`（以 `docs/spec_plus.md` 作为已确认阶段计划；用户要求全自动执行，不新增人工确认门）
- `systematic-debugging`
- `verification-before-completion`

### 风险与待确认
- 真实 DeepSeek API Key、真实 `model_id`、base_url、成本、限流和线上调用验收待后续接入。
- 真实数据库、真实持久化、真实生产部署和运维监控待后续接入。
- 真实 ASR/TTS、麦克风链路、音频保存和隐私策略待后续接入。
- 真实 OCR/PDF layout Provider 和视觉页面检索生产闭环待后续接入。
- 当前 eval 为轻量自研 smoke/mock 评测，不代表真实模型评测或第三方评测框架验收。
- 当前 trace export 为本地 Markdown 产物，不代表生产 observability collector。

### 是否可以进入下一阶段

P0+ 到 P9+ 已完成；本轮全自动升级目标可以结束。后续如需继续，应另开真实 Provider / 生产环境接入阶段。

## 阶段：方案 B 架构修复（P9+ 后）

### 完成时间
2026-07-09 14:05:57 Asia/Shanghai

### 阶段目标
执行用户确认的方案 B：用结构性重构修复 Agent 决策只记录不执行、RAG reviewed chunk 相关性不足仍 confident、安全意图优先级不足、检索排序依赖插入顺序等逻辑问题。

### 完成内容
- 新增 `AgentRuntime`，`AppPipeline` 不再内嵌旧线性业务流程，只委托 runtime 执行。
- 新增 `AgentActionExecutor`，让 `SAFE_RESPONSE`、`ASK_CLARIFICATION`、`REWRITE_ONLY`、`RETRIEVE_MORE`、`STOP` 具备真实执行路径。
- 新增 `safety.policy`，统一检测可执行危险操作细节，并避免把拒绝句误判为危险步骤。
- 重构 `GroundedAnswerGenerator` 的 operation safety 输出，安全意图不复述危险 evidence 原文。
- 新增 `EvidenceCandidate`、`EvidenceRanker`、`EvidenceEligibilityPolicy`，将 RAG 改为 candidate -> rank -> eligibility -> evidence package。
- 删除旧 `src/knowledge/indexes/hybrid_index.py`，不再保留插入顺序式 hybrid 合并路径。
- `RetrievalController` 不再用 `chunks_by_id` 直接收集证据；只有通过相关性资格审查的 reviewed chunk 才进入 `EvidencePackage.evidence_items`。
- `QueryObject` 新增 `safety_flags`，`QueryUnderstandingService` 将维修、故障处置、操作、拆卸、改装等安全意图置于部件意图之前。
- 将缺失证据语义统一为 `no_qualified_evidence`，并在 demo audit 文档中同步更新。

### 修改文件
- `docs/STATUS.md`
- `docs/demo_audit_report.md`
- `docs/release_acceptance.md`
- `src/services/app_pipeline.py`
- `src/input/query_object.py`
- `src/input/query_understanding.py`
- `src/generation/generator.py`
- `src/self_check/boundary_checker.py`
- `src/knowledge/retrieval_controller.py`
- `tests/integration/rag_pipeline/test_basic_retrieval.py`
- `tests/integration/rag_pipeline/test_scene_binding_retrieval.py`
- `tests/integration/app_loop/test_agent_decision_execution.py`

### 新增文件
- `src/agent/__init__.py`
- `src/agent/actions.py`
- `src/agent/runtime.py`
- `src/safety/__init__.py`
- `src/safety/policy.py`
- `src/knowledge/evidence_ranking.py`
- `src/knowledge/evidence_policy.py`
- `tests/unit/input/test_safety_intent_priority.py`
- `tests/unit/knowledge/test_evidence_ranker.py`
- `tests/unit/self_check/test_safety_policy.py`
- `tests/integration/rag_pipeline/test_evidence_relevance_gate.py`
- `docs/trace_reports/scheme_b_refactor_acceptance.md`

### 删除文件
- `src/knowledge/indexes/hybrid_index.py`

### 测试命令
```powershell
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m pytest tests\unit\input\test_safety_intent_priority.py tests\integration\rag_pipeline\test_evidence_relevance_gate.py tests\unit\knowledge\test_evidence_ranker.py tests\integration\app_loop\test_agent_decision_execution.py -q
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m pytest tests\unit\self_check\test_safety_policy.py tests\integration\app_loop\test_agent_decision_execution.py -q
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m pytest
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m compileall -q src scripts
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" scripts\run_eval.py --suite smoke
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" scripts\export_trace_report.py --run-id scheme_b_refactor_acceptance
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" scripts\validate_deployment.py
```

### 测试结果

通过。

- 方案 B 目标测试：`8 passed`。
- 安全策略与 Agent 决策测试：`4 passed`。
- 全量 pytest：`109 passed`，退出码 0。
- compileall：`COMPILEALL=ok`。
- smoke eval：`case_count=3`，`passed_count=3`，`pass_rate=1.0`，`mock_offline=true`。
- trace export：生成 `docs/trace_reports/scheme_b_refactor_acceptance.md`，`mock_offline=true`。
- deployment validate：`status=ok`，`missing_files=[]`，`provider_profile=mock`，`real_provider_pending=true`。

### 关键回归场景
- reviewed 资料只有“发动机提供推力”时，询问“解释一下阻力”不会进入 confident evidence，也不会回答发动机推力。
- mock vector store 单独弱命中不能支撑参数或事实回答。
- 普通问答误检索到危险操作资料时，`SAFE_RESPONSE` 会替换原稿，不返回危险步骤。
- 场景对象与回答目标不一致时，`ASK_CLARIFICATION` 返回澄清 answer，不返回原回答。
- 安全拒绝句不会被安全策略误判为危险操作步骤。

### harness_plus.md 边界检查

* 是否跳阶段：否，本节为 P9+ 后用户明确要求的方案 B 修复。
* 是否提前实现后续阶段：否。
* 是否污染全局 Python 环境：否。
* 是否硬编码密钥或模型配置：否。
* 是否删除测试规避失败：否。
* 是否把 MVP 占位描述为工业级能力：否。
* 是否把 Mock/Offline 验收描述为真实生产验收：否。
* 是否引入未确认大型依赖：否。
* 是否访问真实外部服务：否。

### Mock / Offline 状态

* 是否使用 MockModelClient：是。
* 是否使用 Mock ASR/TTS：是。
* 是否使用 mock embedding：是，但 mock vector 命中不能单独支撑 confident evidence。
* 是否使用 mock visual adapter：是。
* 真实 Provider 是否待后续接入：是。

### 风险与待确认
- 真实 embedding provider、真实向量库和线上 RAG 评测仍待后续接入。
- 真实 DeepSeek、真实数据库、真实 ASR/TTS、真实 OCR/PDF layout 和生产环境仍待后续接入。
- 当前方案 B 修复的是 Mock/Offline 架构正确性，不代表真实 Provider 验收。

### 是否可以进入下一阶段

方案 B 已完成；后续可进入真实 Provider / 生产环境接入阶段。

## 阶段：P8+

### 完成时间
2026-07-09 Asia/Shanghai

### 阶段目标
建立轻量自研评测体系、E2E 场景、trace 导出和部署配置校验，使 Mock/Offline 链路可评测、可审计、可验证。

### 完成内容
- 新增 `configs/evals.yaml`，定义 smoke suite、报告目录、部署校验文件和 mock profile 规则。
- 新增 `TraceReportExporter`，可将 `RunTrace` 导出为 Markdown。
- 新增 `scripts/run_eval.py --suite smoke`，通过正式 `AppPipeline` 和 `MockVoiceLoop` 执行文本、证据不足、语音 mock 三类 smoke case。
- 新增 `scripts/export_trace_report.py --run-id <run_id>`，生成离线 trace report。
- 新增 `scripts/validate_deployment.py`，检查必要配置文件和 mock provider profile。
- 新增 `docs/eval_plan.md` 和 `docs/deployment_checklist.md`。
- 新增 E2E 场景：文本问答、反馈改写、语音 mock、eval/trace/deployment 脚本。
- 运行 smoke eval、trace export 和 deployment validate，并生成 `docs/eval_reports/smoke_eval.json`、`docs/trace_reports/e2e_trace.md`、`docs/trace_reports/p8_trace_smoke.md`。

### 修改文件
- `docs/STATUS.md`

### 新增文件
- `configs/evals.yaml`
- `src/observability/__init__.py`
- `src/observability/trace_exporter.py`
- `scripts/run_eval.py`
- `scripts/export_trace_report.py`
- `scripts/validate_deployment.py`
- `docs/eval_plan.md`
- `docs/deployment_checklist.md`
- `docs/eval_reports/smoke_eval.json`
- `docs/trace_reports/e2e_trace.md`
- `docs/trace_reports/p8_trace_smoke.md`
- `tests/e2e/scenarios/test_text_qa_flow.py`
- `tests/e2e/scenarios/test_feedback_flow.py`
- `tests/e2e/scenarios/test_voice_flow.py`
- `tests/e2e/scenarios/test_eval_and_deployment_scripts.py`

### 删除文件
- 无

### 测试命令
```powershell
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m pytest tests\e2e -q
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m pytest
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" scripts\run_eval.py --suite smoke
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" scripts\export_trace_report.py --run-id p8_trace_smoke
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" scripts\validate_deployment.py
```

### 测试结果
通过，E2E `7 passed`，全量测试 `99 passed in 0.79s`，smoke eval `pass_rate=1.0`，trace export 和 deployment validate 均退出码 0。

### 失败修复记录
- 首次 E2E 出现 2 个失败：trace export 子进程 stdout 在 Windows 中文路径下按 UTF-8 解码失败；feedback E2E 调用了不存在的 `EvidenceLock.build()` 接口。
- 根因分别是脚本 JSON 输出使用非 ASCII 路径文本、E2E 测试未按真实 feedback 函数式接口调用。
- 修复方式：脚本 stdout JSON 改为 ASCII 转义并设置子进程 `PYTHONIOENCODING=utf-8`；feedback E2E 改用 `build_evidence_lock()`、`parse_feedback()`、`plan_rewrite()` 和 `rewrite_answer()`。

### harness_plus.md 边界检查

* 是否跳阶段：否
* 是否提前实现后续阶段：否
* 是否污染全局 Python 环境：否
* 是否硬编码密钥或模型配置：否
* 是否删除测试规避失败：否
* 是否把 MVP 占位描述为工业级能力：否
* 是否把 Mock/Offline 验收描述为真实生产验收：否
* 是否引入未确认大型依赖：否
* 是否访问真实外部服务：否

### Mock / Offline 状态

* 是否使用 MockModelClient：不涉及
* 是否使用 Mock ASR/TTS：是
* 是否使用 mock embedding：不涉及
* 是否使用 mock visual adapter：不涉及
* 真实 Provider 是否待后续接入：是

### 实际调用过的关键 skill
- `using-superpowers`
- `writing-plans`
- `systematic-debugging`
- `verification-before-completion`

### 风险与待确认
- 当前 eval 为轻量自研 smoke/mock 评测，不代表 RAGAS/DeepEval/TruLens 或真实模型评测。
- trace export 基于本地 mock run，不代表生产 observability collector。
- deployment validate 仅验证本地 Mock/Offline 配置完整性，真实生产环境待后续接入。

### 是否可以进入下一阶段
可以。

## 阶段：P7+

### 完成时间
2026-07-09 Asia/Shanghai

### 阶段目标
补齐 Mock-first 语音交互模块，包括语音状态机、Mock ASR/TTS、VAD、barge-in、voice metrics、voice query object 和主 pipeline 入口。

### 完成内容
- 新增 `configs/voice.yaml`，默认 ASR/TTS/VAD 均为 mock，原始音频默认不落盘。
- 新增语音状态机，覆盖 `IDLE`、`LISTENING`、`TRANSCRIBING`、`UNDERSTANDING`、`RETRIEVING`、`GENERATING`、`SPEAKING`、`INTERRUPTED`、`REWRITE`、`CLARIFY`。
- 新增 `MockASRProvider`、`MockTTSProvider`、`MockVADService`、`BargeInController`、`VoiceMetricsRecorder` 和 mock transport。
- 新增航空术语纠错，覆盖“航道比 -> 涵道比”“鸡翼 -> 机翼”。
- 新增 `VoiceQueryNormalizer` 和 `VoiceQueryObject`，低置信 ASR 标记 clarification，不进入正式检索。
- 新增 `MockVoiceLoop`，高置信语音输入进入 `AppPipeline`，不绕过 RAG/生成/自检链路。
- 新增 voice 单元测试和 mock voice loop 集成测试。

### 修改文件
- `docs/STATUS.md`

### 新增文件
- `configs/voice.yaml`
- `src/voice/__init__.py`
- `src/voice/session_state.py`
- `src/voice/asr.py`
- `src/voice/tts.py`
- `src/voice/vad.py`
- `src/voice/terminology.py`
- `src/voice/barge_in.py`
- `src/voice/metrics.py`
- `src/voice/transport.py`
- `src/voice/voice_loop.py`
- `src/input/voice_query_normalizer.py`
- `tests/unit/voice/test_voice_state_machine.py`
- `tests/unit/voice/test_query_normalizer.py`
- `tests/unit/voice/test_voice_components.py`
- `tests/integration/voice_loop/test_mock_voice_loop.py`

### 删除文件
- 无

### 测试命令
```powershell
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m pytest tests\unit\voice tests\integration\voice_loop -q
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m pytest
rg -n "raw_audio|persist|MockASR|MockTTS|low_confidence|barge|VoiceState|ASRResult|TTSResult|voice_query" src configs tests\unit\voice tests\integration\voice_loop
```

### 测试结果
通过，voice 专项测试 `9 passed`，全量测试 `92 passed in 0.47s`。

### harness_plus.md 边界检查

* 是否跳阶段：否
* 是否提前实现后续阶段：否
* 是否污染全局 Python 环境：否
* 是否硬编码密钥或模型配置：否
* 是否删除测试规避失败：否
* 是否把 MVP 占位描述为工业级能力：否
* 是否把 Mock/Offline 验收描述为真实生产验收：否
* 是否引入未确认大型依赖：否
* 是否访问真实外部服务：否

### Mock / Offline 状态

* 是否使用 MockModelClient：不涉及
* 是否使用 Mock ASR/TTS：是
* 是否使用 mock embedding：不涉及
* 是否使用 mock visual adapter：不涉及
* 真实 Provider 是否待后续接入：是

### 实际调用过的关键 skill
- `using-superpowers`
- `writing-plans`
- `verification-before-completion`

### 风险与待确认
- 当前语音链路为 Mock ASR/TTS/VAD，不代表真实麦克风、真实 ASR/TTS Provider 或低延迟实时语音能力。
- 原始音频默认不落盘；真实音频保存策略需后续产品与隐私确认。
- 真实 Provider、浏览器音频协议和 WebSocket/WebRTC 接入待后续阶段确认。

### 是否可以进入下一阶段
可以。

## 阶段：P6+

### 完成时间
2026-07-09 Asia/Shanghai

### 阶段目标
将 Prompt 从内置小型 store 升级为可版本化、可快照、可回滚的资产库，覆盖教学、知识讲解、语音、自检和兜底模板边界。

### 完成内容
- `configs/prompts.yaml` 新增 `asset_dir`、`snapshot_dir` 和 rollback policy。
- 新增 `assets/prompts/**` 外置 Prompt 资产，覆盖教学解释、知识讲解、语音候选、自检和兜底模板。
- Prompt 资产增加 `risk_boundaries`、`snapshot_id`，Prompt 内容版本增加 `snapshot_id`。
- `PromptAssetStore.from_config()` 优先读取外置资产目录，缺失时保留旧默认模板 fallback。
- `PromptAssetStore` 支持同一 template_id 的版本历史、`activate_version()` 和 `rollback_prompt()`。
- 未审核的 `aviation_voice_spoken` 保持 `candidate` 状态，不能进入 runtime active。
- 新增 Prompt snapshot fixture，并测试 active asset 与 snapshot 对齐。
- 新增版本化、candidate 阻断、快照和 rollback 测试。

### 修改文件
- `configs/prompts.yaml`
- `src/prompts/asset_models.py`
- `src/prompts/asset_store.py`
- `docs/STATUS.md`

### 新增文件
- `assets/prompts/aviation_basic_safe.yaml`
- `assets/prompts/aviation_explain_active.yaml`
- `assets/prompts/aviation_knowledge_explain.yaml`
- `assets/prompts/aviation_self_check.yaml`
- `assets/prompts/aviation_voice_spoken.yaml`
- `tests/fixtures/prompt_snapshots/aviation_explain_active_v2.json`
- `tests/unit/prompts/test_prompt_versioning.py`

### 删除文件
- 无

### 测试命令
```powershell
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m pytest tests\unit\prompts -q
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m pytest
rg -n "status: candidate|status: active|snapshot_id|parent_version|rollback|asset_dir|candidate_not_runtime|PromptAssetStore|activate_version" assets configs src\prompts tests\unit\prompts
```

### 测试结果
通过，Prompt 专项测试 `12 passed`，全量测试 `83 passed in 0.40s`。

### harness_plus.md 边界检查

* 是否跳阶段：否
* 是否提前实现后续阶段：否
* 是否污染全局 Python 环境：否
* 是否硬编码密钥或模型配置：否
* 是否删除测试规避失败：否
* 是否把 MVP 占位描述为工业级能力：否
* 是否把 Mock/Offline 验收描述为真实生产验收：否
* 是否引入未确认大型依赖：否
* 是否访问真实外部服务：否

### Mock / Offline 状态

* 是否使用 MockModelClient：不涉及
* 是否使用 Mock ASR/TTS：不涉及
* 是否使用 mock embedding：不涉及
* 是否使用 mock visual adapter：不涉及
* 真实 Provider 是否待后续接入：是

### 实际调用过的关键 skill
- `using-superpowers`
- `writing-plans`
- `verification-before-completion`

### 风险与待确认
- Prompt 内容仍是离线初始模板，需要后续人工评审和真实评测反馈后再扩大 active 范围。
- `aviation_voice_spoken` 已存在但保持 candidate，P7+ 可在语音链路中继续完善，未提前启用。
- Prompt snapshot 目前为轻量 fixture，P8+ 需纳入正式 eval/smoke 回归。

### 是否可以进入下一阶段
可以。

## 阶段：P5+

### 完成时间
2026-07-09 Asia/Shanghai

### 阶段目标
补齐多模态对象、页面区域、bbox、layout trace、视觉证据契约和 mock visual adapter，明确缺少 OCR/layout 时的 incomplete 状态。

### 完成内容
- 新增 `ImageInput`、`BoundingBox`、`LayoutTrace`、`PageRegion`、`VisualEvidenceItem` 契约。
- 将 `VisualAsset.bbox` 从裸 tuple 升级为 `BoundingBox`，并增加 `layout_trace`、`incomplete_reasons` 和 metadata。
- 新增 `MockVisualAdapter`，默认输出 mock region，并标记 `visual_evidence_incomplete`。
- `PdfIngestor` 的 mock layout trace 明确标记 OCR/layout 未启用。
- `VisualIngestor` 可将 PageRegion 转为 VisualEvidenceItem；无 text cross-check 时不可作为核心事实证据。
- `VisualPageIndex` 支持 PageRegion 的索引与标签检索。
- `EvidencePackageBuilder` 新增视觉证据转换入口，保留 bbox、layout trace、text cross-check 和 incomplete reasons。
- `configs/rag.yaml` 补齐 visual_search、OCR/layout 开关、text cross-check 要求和视觉处理限制。
- 新增多模态契约测试，覆盖 bbox、layout trace、mock adapter、visual evidence gate。

### 修改文件
- `configs/rag.yaml`
- `src/knowledge/schemas.py`
- `src/knowledge/ingestion/pdf_ingestor.py`
- `src/knowledge/ingestion/visual_ingestor.py`
- `src/knowledge/indexes/visual_page_index.py`
- `src/knowledge/evidence_package.py`
- `tests/integration/rag_pipeline/test_scene_binding_retrieval.py`
- `docs/STATUS.md`

### 新增文件
- `tests/unit/knowledge/test_multimodal_contracts.py`

### 删除文件
- 无

### 测试命令
```powershell
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m pytest tests\unit\knowledge tests\integration\rag_pipeline tests\unit\self_check -q
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m pytest
rg -n "visual_evidence_incomplete|missing_text_cross_check|text_cross_check|usable_as_core_evidence|BoundingBox|LayoutTrace|PageRegion|VisualEvidenceItem|MockVisualAdapter" src tests configs
```

### 测试结果
通过，knowledge/RAG/self_check 相关测试 `19 passed`，全量测试 `78 passed in 0.48s`。

### harness_plus.md 边界检查

* 是否跳阶段：否
* 是否提前实现后续阶段：否
* 是否污染全局 Python 环境：否
* 是否硬编码密钥或模型配置：否
* 是否删除测试规避失败：否
* 是否把 MVP 占位描述为工业级能力：否
* 是否把 Mock/Offline 验收描述为真实生产验收：否
* 是否引入未确认大型依赖：否
* 是否访问真实外部服务：否

### Mock / Offline 状态

* 是否使用 MockModelClient：不涉及
* 是否使用 Mock ASR/TTS：不涉及
* 是否使用 mock embedding：不涉及
* 是否使用 mock visual adapter：是
* 真实 Provider 是否待后续接入：是

### 实际调用过的关键 skill
- `using-superpowers`
- `writing-plans`
- `verification-before-completion`

### 风险与待确认
- 当前视觉处理为 mock adapter，不代表真实 OCR/PDF layout 能力。
- 无 text cross-check 的视觉线索只可作为候选或 incomplete evidence，不能支撑核心航空事实。
- 真实 OCR/PDF layout Provider 和大图处理策略待后续接入。

### 是否可以进入下一阶段
可以。

## 阶段：P4+

### 完成时间
2026-07-09 Asia/Shanghai

### 阶段目标
将 RAG 从仅有 token similarity 的 MVP 检索升级为具备 `EmbeddingProvider`、`VectorStore`、mock embedding 和明确 fallback trace 的工程化知识库边界。

### 完成内容
- 新增 `EmbeddingProvider` 协议、`EmbeddingResult` 和 `MockEmbeddingProvider`。
- 新增 `VectorStore` 协议、`VectorSearchResult` 和 `InMemoryVectorStore`。
- 为 `SimpleVectorIndex` 增加 `simple_token_similarity`、`is_embedding_index=false` 和 fallback reason 标识。
- `RetrievalController` 默认使用 `MockEmbeddingProvider + InMemoryVectorStore`，同时保留 token similarity fallback。
- 检索 trace 新增 embedding provider、是否 mock、vector store provider、fallback index 和 fallback reason。
- `configs/rag.yaml` 补齐 embedding provider、dimension、vector store provider、fallback index 和 trace 开关。
- 新增 VectorStore/EmbeddingProvider 单元测试和 RAG trace 集成测试。

### 修改文件
- `configs/rag.yaml`
- `src/knowledge/indexes/vector_index.py`
- `src/knowledge/retrieval_controller.py`
- `docs/STATUS.md`

### 新增文件
- `src/services/embedding_provider.py`
- `src/knowledge/indexes/vector_store.py`
- `tests/unit/knowledge/test_vector_store_contract.py`
- `tests/integration/rag_pipeline/test_vector_adapter_trace.py`

### 删除文件
- 无

### 测试命令
```powershell
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m pytest tests\unit\knowledge tests\integration\rag_pipeline -q
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m pytest
rg -n "simple_token_similarity|token similarity|fallback|embedding_provider|VectorStore|EmbeddingProvider|SimpleVectorIndex" src configs tests docs\STATUS.md docs\upgrade_audit.md
```

### 测试结果
通过，RAG/knowledge 相关测试 `9 passed`，全量测试 `74 passed in 0.39s`。

### harness_plus.md 边界检查

* 是否跳阶段：否
* 是否提前实现后续阶段：否
* 是否污染全局 Python 环境：否
* 是否硬编码密钥或模型配置：否
* 是否删除测试规避失败：否
* 是否把 MVP 占位描述为工业级能力：否
* 是否把 Mock/Offline 验收描述为真实生产验收：否
* 是否引入未确认大型依赖：否
* 是否访问真实外部服务：否

### Mock / Offline 状态

* 是否使用 MockModelClient：不涉及
* 是否使用 Mock ASR/TTS：不涉及
* 是否使用 mock embedding：是
* 是否使用 mock visual adapter：不涉及
* 真实 Provider 是否待后续接入：是

### 实际调用过的关键 skill
- `using-superpowers`
- `writing-plans`
- `verification-before-completion`

### 风险与待确认
- 当前 embedding provider 为 mock，vector store 为内存实现，不代表真实向量库生产能力。
- Chroma、FAISS、sentence-transformers 未引入，仍待 Python 3.13 + Windows 兼容性核验。
- `SimpleVectorIndex` 保留为 token similarity fallback，不得描述为工业级 embedding 检索。

### 是否可以进入下一阶段
可以。

## 阶段：P3+

### 完成时间
2026-07-09 Asia/Shanghai

### 阶段目标
建立 `deepseek-flash` 统一模型调用层、MockModelClient、DeepSeek adapter 边界、结构化输出校验、重试策略和规则生成 fallback。

### 完成内容
- 新增 `ModelClient` 协议、`ModelMessage`、`ModelOptions`、`ModelResult` 和模型错误分类。
- 新增 `MockModelClient`，默认可在无密钥环境下返回结构化输出。
- 新增 `DeepSeekModelClient` adapter，使用标准库 HTTP 客户端，缺少密钥或 model_id 时返回结构化错误，不访问真实网络。
- 新增 `StructuredOutputValidator` 和 `ANSWER_ENVELOPE_SCHEMA`，校验模型输出必须为 JSON object 且包含核心字段。
- 新增轻量 `RetryPolicy`，覆盖可重试限流错误。
- 将 `GroundedAnswerGenerator` 改为可选注入 `ModelClient`；模型输出有效时生成结构化 answer，模型失败或结构化错误时回落规则 generator。
- 补齐 `configs/providers.yaml` 中 DeepSeek adapter 的 `chat_path`、fallback、日志和 rate limit 配置项。
- 新增模型服务和生成器回退测试。
- 搜索确认 `urllib`、Authorization、DeepSeek adapter 只存在于 `src/services/**`，业务模块无直连模型 API。

### 修改文件
- `configs/providers.yaml`
- `src/generation/generator.py`
- `docs/STATUS.md`

### 新增文件
- `src/services/model_client.py`
- `src/services/deepseek_client.py`
- `src/services/structured_output.py`
- `src/services/retry_policy.py`
- `tests/unit/services/test_model_client.py`
- `tests/unit/generation/test_model_generation.py`

### 删除文件
- 无

### 测试命令
```powershell
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m pytest tests\unit\services tests\unit\generation -q
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m pytest
rg -n "deepseek|DeepSeek|urllib|httpx|requests|Authorization|Bearer" src tests configs docs -g '!src/services/**'
```

### 测试结果
通过，服务/生成测试 `12 passed`，全量测试 `71 passed in 0.40s`。边界搜索未发现业务模块直连模型 API。

### 失败修复记录
- 首次新增模型测试出现 1 个失败：结构化校验将 `claim_candidates: []` 误判为缺失字段。
- 根因是 validator 把空列表等同于缺字段，但空 claim 列表对澄清或无事实主张回答是合法结构。
- 修复方式：缺失判定改为字段不存在、`None`、空字符串或空对象；空列表保留为合法值。

### harness_plus.md 边界检查

* 是否跳阶段：否
* 是否提前实现后续阶段：否
* 是否污染全局 Python 环境：否
* 是否硬编码密钥或模型配置：否
* 是否删除测试规避失败：否
* 是否把 MVP 占位描述为工业级能力：否
* 是否把 Mock/Offline 验收描述为真实生产验收：否
* 是否引入未确认大型依赖：否
* 是否访问真实外部服务：否

### Mock / Offline 状态

* 是否使用 MockModelClient：是
* 是否使用 Mock ASR/TTS：不涉及
* 是否使用 mock embedding：不涉及
* 是否使用 mock visual adapter：不涉及
* 真实 Provider 是否待后续接入：是

### 实际调用过的关键 skill
- `using-superpowers`
- `writing-plans`
- `systematic-debugging`
- `verification-before-completion`

### 风险与待确认
- `DeepSeekModelClient` 仅实现 adapter 边界，默认不启用真实调用；真实 `DEEPSEEK_MODEL`、base_url、密钥和线上验收待后续接入。
- P3+ 没有把规则 generator 描述为真实 LLM 能力；规则路径仍是 fallback。
- P6+ 仍需将 prompt 资产进一步外置和版本化，当前 P3+ 只保留最小结构化输出指令。

### 是否可以进入下一阶段
可以。

## 阶段：P2+

### 完成时间
2026-07-09 Asia/Shanghai

### 阶段目标
建立 CLI-first 应用入口、service/pipeline facade、健康检查、`run_id`、统一结构化错误响应和 app loop smoke 测试。

### 完成内容
- 新增 `src/services/app_pipeline.py`，串联现有 input、retrieval、generation、self_check 模块，API/CLI 不复制业务逻辑。
- 新增 `TextQueryRequest`、`TextQueryResponse`、`HealthStatus` 和 `ErrorResponse` 等本地 API 契约。
- 新增 CLI 入口 `src/app/cli.py` 和 `src/app/main.py`，输出 UTF-8 JSON。
- `configs/app.yaml` 补齐 `entry_mode`、host、port 和 trace 开关。
- 新增 `docs/api_contracts.md`，记录 P2+ 请求、响应、错误和 CLI 命令契约。
- 新增 app loop 集成测试，覆盖 health check、pipeline smoke、结构化错误和 CLI 子进程 smoke。
- 直接运行 CLI smoke，确认无资料时返回结构化 clarification，不伪造证据。

### 修改文件
- `configs/app.yaml`
- `docs/STATUS.md`

### 新增文件
- `docs/api_contracts.md`
- `src/app/__init__.py`
- `src/app/cli.py`
- `src/app/main.py`
- `src/app/api/__init__.py`
- `src/app/api/errors.py`
- `src/app/api/schemas.py`
- `src/services/__init__.py`
- `src/services/app_pipeline.py`
- `tests/integration/app_loop/test_cli_pipeline.py`

### 删除文件
- 无

### 测试命令
```powershell
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m pytest tests\integration\app_loop -q
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m pytest
$env:PYTHONPATH='src'; $env:PYTHONIOENCODING='utf-8'; & "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m app.cli --query "解释一下升力" --run-id "run_p2_cli_smoke" --no-trace
```

### 测试结果
通过，app loop 测试 `4 passed`，全量测试 `64 passed in 0.38s`，CLI smoke 退出码为 0。

### harness_plus.md 边界检查

* 是否跳阶段：否
* 是否提前实现后续阶段：否
* 是否污染全局 Python 环境：否
* 是否硬编码密钥或模型配置：否
* 是否删除测试规避失败：否
* 是否把 MVP 占位描述为工业级能力：否
* 是否把 Mock/Offline 验收描述为真实生产验收：否
* 是否引入未确认大型依赖：否
* 是否访问真实外部服务：否

### Mock / Offline 状态

* 是否使用 MockModelClient：不涉及
* 是否使用 Mock ASR/TTS：不涉及
* 是否使用 mock embedding：不涉及
* 是否使用 mock visual adapter：不涉及
* 真实 Provider 是否待后续接入：是

### 实际调用过的关键 skill
- `using-superpowers`
- `writing-plans`
- `verification-before-completion`

### 风险与待确认
- HTTP API 仍为 optional，未引入 FastAPI/uvicorn；如后续需要，须先确认 Python 3.13 + Windows 兼容性。
- 默认 CLI 不预置真实资料库，因此无资料时返回保守 clarification；演示资料与 eval 数据在后续阶段治理。
- P2+ 不接入模型，P3+ 仍需实现统一 ModelClient 和结构化生成边界。

### 是否可以进入下一阶段
可以。

## 阶段：P1+

### 完成时间
2026-07-09 Asia/Shanghai

### 阶段目标
完成 UTF-8 中文编码回归、Mock-first provider 配置补齐、`.env.example` 和 Windows 虚拟环境命令文档化，并保持全量测试通过。

### 完成内容
- 新增 UTF-8 文本文件扫描测试，覆盖项目 Markdown、Python、YAML、TOML、TXT 和 `.env.example` 等文本文件。
- 新增中文检索到规则生成的回归测试，确认中文航空术语可在本地链路中保持 UTF-8。
- 将 `configs/providers.yaml` 从 `pending_confirmation` 调整为默认 `mock` profile，并保留 DeepSeek adapter 的环境变量边界与 `model_id: pending_confirmation`。
- 补齐 `.env.example` 中 DeepSeek 相关环境变量名，不写入真实密钥或真实服务地址。
- 更新 `README.md`，明确必须使用指定虚拟环境解释器运行 pytest，并声明 Mock-first / Offline-first 策略。
- 更新已有配置加载测试，使其匹配 P1+ 的 mock provider 默认值。

### 修改文件
- `.env.example`
- `README.md`
- `configs/providers.yaml`
- `tests/unit/core/test_state_machine.py`
- `docs/STATUS.md`

### 新增文件
- `tests/unit/core/test_encoding_and_config.py`

### 删除文件
- 无

### 测试命令
```powershell
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m pytest tests\unit\core\test_encoding_and_config.py -q
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m pytest
```

### 测试结果
通过，新增测试 `3 passed`，全量测试 `60 passed in 0.26s`。

### 失败修复记录
- 首次运行新增 UTF-8 扫描测试时出现 1 个失败：测试文件自身包含要检测的乱码标记常量，扫描到自身后误报。
- 根因是测试夹具把“坏样例”写进了被扫描范围，不是项目文件出现乱码。
- 修复方式：将乱码标记改为运行时按 Unicode code point 构造，保持扫描标准不变。

### harness_plus.md 边界检查

* 是否跳阶段：否
* 是否提前实现后续阶段：否
* 是否污染全局 Python 环境：否
* 是否硬编码密钥或模型配置：否
* 是否删除测试规避失败：否
* 是否把 MVP 占位描述为工业级能力：否
* 是否把 Mock/Offline 验收描述为真实生产验收：否
* 是否引入未确认大型依赖：否
* 是否访问真实外部服务：否

### Mock / Offline 状态

* 是否使用 MockModelClient：不涉及
* 是否使用 Mock ASR/TTS：不涉及
* 是否使用 mock embedding：是，配置默认 `providers.embedding.default: mock`
* 是否使用 mock visual adapter：不涉及
* 真实 Provider 是否待后续接入：是

### 实际调用过的关键 skill
- `using-superpowers`
- `writing-plans`
- `systematic-debugging`
- `verification-before-completion`

### 风险与待确认
- 当前只是配置层 mock profile，真正 `MockModelClient` 和 DeepSeek adapter 在 P3+ 实现。
- `DEEPSEEK_MODEL`、真实 base_url 和结构化输出能力仍待 P3+ 配置核验。
- 向量检索仍未升级，`simple_token_similarity` 只作为 P4+ 前的 fallback 标记。

### 是否可以进入下一阶段
可以。

## 阶段：Task 2 (P1 Prompt Asset Repository Migration)

### 完成时间
2026-07-09 Asia/Shanghai

### 阶段目标
修复 Task 2 评审问题，收紧 Prompt Repository 校验，并统一 assembler 输出契约。

### 已完成内容
- 为 `PromptAssetRepository` 增加运行态版本 `snapshot_id` 与评估快照 `snapshot_id` 的严格一致性校验。
- 新增聚焦回归测试，验证快照 id 不一致时 repository 会拒绝加载运行态 prompt。
- 删除 `src/prompts/assembler.py` 中重复定义的 `MessageBundle`。
- `PromptAssembler` 现在返回 canonical `PromptMessageBundle`。
- `PromptMessageBundle.messages` 现在只包含 `services.model_client.ModelMessage`。
- assembler 输出只使用标准角色 `system` 和 `user`。
- 清理 `docs/STATUS.md` 与 `docs/superpowers/plans/_sdd/task-2-report.md` 中 Task 2 相关乱码和无关流程描述。
- 补充 `docs/spec.md` 的 P1 Prompt Refactor Update，并修正其中遗留的 `asset_store.py`、`MessageBundle` 和旧资产存储说明。
- 在 `docs/harness.md` 顶部新增 `P1 Prompt Refactor Update`，用干净文本重述 Prompt 安全边界，避免顶部乱码影响审计。

### 修改文件
- `src/prompts/repository.py`
- `tests/unit/prompts/test_prompt_repository.py`
- `src/prompts/assembler.py`
- `tests/unit/prompts/test_prompt_assembler.py`
- `docs/spec.md`
- `docs/harness.md`
- `docs/STATUS.md`
- `docs/superpowers/plans/_sdd/task-2-report.md`

### 新增文件
- 无

### 删除文件
- 无

### 测试命令
```powershell
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m pytest tests/unit/prompts/test_prompt_repository.py tests/unit/prompts/test_prompt_assembler.py -q
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m pytest tests/unit/prompts/test_prompt_models.py tests/unit/prompts/test_prompt_repository.py tests/unit/prompts/test_prompt_assembler.py -q
```

### 测试结果
- `tests/unit/prompts/test_prompt_repository.py tests/unit/prompts/test_prompt_assembler.py -q`：`9 passed`
- `tests/unit/prompts/test_prompt_models.py tests/unit/prompts/test_prompt_repository.py tests/unit/prompts/test_prompt_assembler.py -q`：`14 passed`
- 第二条命令通过。

### 是否违反 harness.md
否。

### 未完成事项
- 无新的 Task 2 阻塞项。

### 下一阶段是否可以开始
可以。

```

## docs/superpowers/plans/_sdd/task-2-report.md

```
# Task 2 Report: Review Fixes

## Summary

This update closes the remaining Task 2 documentation audit finding without restoring `PromptAssetStore`, flat YAML prompt assets, or compatibility shims.

## Fixes

### Repository validation

- `PromptAssetRepository` now verifies that the runtime version `snapshot_id` exactly matches the loaded evaluation snapshot `snapshot_id`.
- Runtime `active` and `experimental` versions still require a present snapshot and `final_decision == "approve"`.
- Added a focused regression test proving a mismatched snapshot id is rejected.

### Assembler contract

- Removed the duplicate `MessageBundle` contract from `src/prompts/assembler.py`.
- `PromptAssembler` now returns the canonical `PromptMessageBundle`.
- `PromptMessageBundle.messages` now contains only `services.model_client.ModelMessage`.
- Message roles now use only `system` and `user`.
- Updated assembler tests to validate the canonical contract.

### Documentation cleanup

- Added a clean `P1 Prompt Refactor Update` section to `docs/spec.md`.
- Replaced the stale `src/prompts/asset_store.py` references in `docs/spec.md` with `src/prompts/repository.py`.
- Replaced the stale `MessageBundle` return contract in `docs/spec.md` with `PromptMessageBundle`.
- Clarified in `docs/spec.md` that prompt assets live under `assets/prompts/<template_id>/asset.json`, `versions/*.json`, and `evaluations/*.json`.
- Clarified in `docs/spec.md` that `PromptAssetRepository` is the asset/version/snapshot loader, that runtime `active`/`experimental` versions require approved matching snapshot ids, that trace output does not store full prompt text, and that Prompt assets do not provide aviation facts.
- Added a clean `P1 Prompt Refactor Update` section near the top of `docs/harness.md` so the P1 prompt safety boundary is auditable even though older text near the top still contains mojibake.
- Restated in `docs/harness.md` that `PromptAssembler` returns `PromptMessageBundle`, bundle messages are `ModelMessage` with only `system` and `user` roles, and old flat YAML assets / `PromptAssetStore` / `default_prompt_assets` / `role="context"` are prohibited.
- Updated the Task 2 entry in `docs/STATUS.md` to note the documentation audit follow-up.

## Files changed

- `src/prompts/repository.py`
- `tests/unit/prompts/test_prompt_repository.py`
- `src/prompts/assembler.py`
- `tests/unit/prompts/test_prompt_assembler.py`
- `docs/STATUS.md`
- `docs/superpowers/plans/_sdd/task-2-report.md`

## Validation record

### Command 1

```powershell
rg -n "asset_store.py|PromptAssetStore|default_prompt_assets|MessageBundle|role=\"context\"|role: context" docs/spec.md docs/harness.md src tests scripts
```

Output:

```text
docs/spec.md:174:- `src/prompts/repository.py` 宸叉浛浠ｅ垹闄ょ殑 `src/prompts/asset_store.py`銆?docs/spec.md:177:- `PromptAssembler` 杩斿洖 canonical `PromptMessageBundle`锛屼笉瀛樺湪鐙珛鐨?`MessageBundle` 杩愯鏃跺绾︺€?docs/spec.md:178:- `PromptMessageBundle.messages` 鍙兘鍖呭惈 `services.model_client.ModelMessage`锛岃鑹插彧鍏佽 `system` 鍜?`user`銆?docs/spec.md:181:- 鏃у钩閾?YAML Prompt 璧勪骇銆乣PromptAssetStore`銆乣default_prompt_assets`銆佷互鍙?`role="context"` / `role: context` 娑堟伅瑙掕壊閮芥槸绂佹椤广€?docs/spec.md:315:assemble_messages(prompt_selection, evidence_package, memory_context, output_contract) -> PromptMessageBundle
docs/harness.md:23:- `src/prompts/repository.py` 鏇夸唬宸插垹闄ょ殑 `src/prompts/asset_store.py`銆?docs/harness.md:26:- `PromptAssembler` 蹇呴』杈撳嚭 canonical `PromptMessageBundle`锛屼笉寰楀啀瀹氫箟鎴栬繑鍥炵嫭绔?`MessageBundle`銆?docs/harness.md:30:- 鏃у钩閾?YAML 璧勪骇銆乣PromptAssetStore`銆乣default_prompt_assets` 鍜屼换浣?`role="context"` / `role: context` 缁勮鏂瑰紡鍧囦负绂佹椤广€?tests\unit\prompts\test_prompt_models.py:7:    PromptMessageBundle,
tests\unit\prompts\test_prompt_models.py:19:    assert asset_models.PromptMessageBundle.__module__ == "prompts.asset_models"
tests\unit\prompts\test_prompt_models.py:37:    bundle = PromptMessageBundle(
src\prompts\assembler.py:5:from prompts.asset_models import PromptMessageBundle, PromptSelection
src\prompts\assembler.py:22:    ) -> PromptMessageBundle:
src\prompts\assembler.py:48:        return PromptMessageBundle(
src\prompts\assembler.py:70:def assemble_messages(prompt_selection: PromptSelection, **kwargs: Any) -> PromptMessageBundle:
src\prompts\asset_models.py:76:class PromptMessageBundle(BaseContract):
```

### Command 2

```powershell
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m pytest tests/unit/prompts -q
```

Output:

```text
...................                                                      [100%]
```

## Result

- The doc audit strings now appear only in current-code names or in explicit prohibition/clarification text.
- `tests/unit/prompts -q` passed on the final verification run.
```

## src/prompts/repository.py

```
from __future__ import annotations

import json
from pathlib import Path
from typing import Any

from core.settings import parse_simple_yaml
from prompts.asset_models import (
    PromptContentVersion,
    PromptEvaluationSnapshot,
    PromptStatus,
    PromptVariableSpec,
    TeachingPromptAsset,
)


class PromptRepositoryError(Exception):
    pass


class PromptAssetRepository:
    def __init__(
        self,
        asset_dir: str | Path,
        allowed_runtime_statuses: list[str] | None = None,
    ) -> None:
        self.asset_dir = Path(asset_dir)
        self.allowed_runtime_statuses = set(allowed_runtime_statuses or ["active", "experimental"])
        self._assets: dict[str, TeachingPromptAsset] = {}
        self._versions: dict[str, dict[str, PromptContentVersion]] = {}
        self._snapshots: dict[tuple[str, str], PromptEvaluationSnapshot] = {}
        self._load()

    @classmethod
    def from_config(cls, path: str | Path = "configs/prompts.yaml") -> "PromptAssetRepository":
        config_path = Path(path)
        config = parse_simple_yaml(config_path.read_text(encoding="utf-8"))
        prompts = config.get("prompts", {})
        asset_dir = prompts.get("asset_dir")
        if not asset_dir:
            raise PromptRepositoryError("prompts.asset_dir is required")
        resolved_asset_dir = Path(asset_dir)
        if not resolved_asset_dir.is_absolute():
            resolved_asset_dir = (config_path.parent.parent / resolved_asset_dir).resolve()
        return cls(
            resolved_asset_dir,
            allowed_runtime_statuses=prompts.get("allowed_runtime_statuses", ["active", "experimental"]),
        )

    def get_asset(self, template_id: str) -> TeachingPromptAsset:
        try:
            return self._assets[template_id]
        except KeyError as exc:
            raise PromptRepositoryError(f"prompt asset not found: {template_id}") from exc

    def get_version(self, template_id: str, version: str) -> PromptContentVersion:
        try:
            return self._versions[template_id][version]
        except KeyError as exc:
            raise PromptRepositoryError(f"prompt version not found: {template_id}@{version}") from exc

    def get_active_version(self, template_id: str) -> PromptContentVersion:
        asset = self.get_asset(template_id)
        return self.get_version(template_id, asset.active_version)

    def get_evaluation_snapshot(self, template_id: str, version: str) -> PromptEvaluationSnapshot:
        try:
            return self._snapshots[(template_id, version)]
        except KeyError as exc:
            raise PromptRepositoryError(f"evaluation snapshot not found: {template_id}@{version}") from exc

    def find_for_intent(self, intent_type: str) -> TeachingPromptAsset | None:
        for template_id in sorted(self._assets):
            asset = self._assets[template_id]
            if asset.status.value not in self.allowed_runtime_statuses:
                continue
            if intent_type in asset.activation_scope:
                return asset
        return None

    def list_versions(self, template_id: str) -> list[str]:
        return sorted(self._versions.get(template_id, {}))

    def _load(self) -> None:
        if not self.asset_dir.exists():
            raise PromptRepositoryError(f"prompt asset directory does not exist: {self.asset_dir}")
        for asset_root in sorted(path for path in self.asset_dir.iterdir() if path.is_dir()):
            asset = self._load_asset(asset_root / "asset.json")
            versions = self._load_versions(asset_root / "versions")
            snapshots = self._load_snapshots(asset_root / "evaluations")
            self._validate_asset(asset, versions, snapshots)
            self._assets[asset.template_id] = asset
            self._versions[asset.template_id] = versions
            self._snapshots.update(snapshots)

    def _validate_asset(
        self,
        asset: TeachingPromptAsset,
        versions: dict[str, PromptContentVersion],
        snapshots: dict[tuple[str, str], PromptEvaluationSnapshot],
    ) -> None:
        if asset.active_version not in versions:
            raise PromptRepositoryError(f"active version missing: {asset.template_id}@{asset.active_version}")
        if asset.status in {PromptStatus.ACTIVE, PromptStatus.EXPERIMENTAL}:
            active_version = versions[asset.active_version]
            if not active_version.snapshot_id:
                raise PromptRepositoryError(
                    f"runtime active version missing snapshot id: {asset.template_id}@{asset.active_version}"
                )
            snapshot_key = (asset.template_id, asset.active_version)
            if snapshot_key not in snapshots:
                raise PromptRepositoryError(
                    f"runtime active version missing evaluation snapshot: {asset.template_id}@{asset.active_version}"
                )
            if snapshots[snapshot_key].final_decision != "approve":
                raise PromptRepositoryError(
                    f"runtime active version lacks approval: {asset.template_id}@{asset.active_version}"
                )
            if snapshots[snapshot_key].snapshot_id != active_version.snapshot_id:
                raise PromptRepositoryError(
                    f"runtime active version snapshot mismatch: {asset.template_id}@{asset.active_version}"
                )

    def _load_asset(self, path: Path) -> TeachingPromptAsset:
        data = self._read_json(path)
        return TeachingPromptAsset(
            template_id=data["template_id"],
            title=data["title"],
            task_type=data["task_type"],
            concept_scope=list(data.get("concept_scope", [])),
            scene_scope=list(data.get("scene_scope", [])),
            status=PromptStatus(data["status"]),
            active_version=data["active_version"],
            activation_scope=list(data.get("activation_scope", [])),
            constraints=list(data.get("constraints", [])),
            risk_boundaries=list(data.get("risk_boundaries", [])),
        )

    def _load_versions(self, root: Path) -> dict[str, PromptContentVersion]:
        if not root.exists():
            raise PromptRepositoryError(f"versions directory missing: {root}")
        versions: dict[str, PromptContentVersion] = {}
        for path in sorted(root.glob("*.json")):
            data = self._read_json(path)
            version = PromptContentVersion(
                template_id=data["template_id"],
                version=data["version"],
                content=data["content"],
                parent_version=data.get("parent_version"),
                change_reason=data.get("change_reason", ""),
                created_at=data.get("created_at", ""),
                review_status=PromptStatus(data["review_status"]),
                snapshot_id=data.get("snapshot_id"),
                variables=[self._load_variable(item) for item in data.get("variables", [])],
            )
            versions[version.version] = version
        return versions

    def _load_snapshots(self, root: Path) -> dict[tuple[str, str], PromptEvaluationSnapshot]:
        if not root.exists():
            return {}
        snapshots: dict[tuple[str, str], PromptEvaluationSnapshot] = {}
        for path in sorted(root.glob("*.json")):
            data = self._read_json(path)
            snapshot = PromptEvaluationSnapshot(
                snapshot_id=data["snapshot_id"],
                template_id=data["template_id"],
                version=data["version"],
                test_cases=list(data.get("test_cases", [])),
                dimension_scores=dict(data.get("dimension_scores", {})),
                violations=list(data.get("violations", [])),
                improvements=list(data.get("improvements", [])),
                final_decision=data["final_decision"],
            )
            snapshots[(snapshot.template_id, snapshot.version)] = snapshot
        return snapshots

    def _load_variable(self, item: dict[str, Any]) -> PromptVariableSpec:
        return PromptVariableSpec(
            name=item["name"],
            source=item["source"],
            value_type=item.get("value_type", "text"),
            required=bool(item.get("required", False)),
            missing_behavior=item.get("missing_behavior", "omit"),
            description=item.get("description", ""),
        )

    def _read_json(self, path: Path) -> dict[str, Any]:
        if not path.exists():
            raise PromptRepositoryError(f"prompt repository file missing: {path}")
        return json.loads(path.read_text(encoding="utf-8"))
```

## src/prompts/assembler.py

```
from __future__ import annotations

from typing import Any

from prompts.asset_models import PromptMessageBundle, PromptSelection
from prompts.router import PromptRouterConfig
from services.model_client import ModelMessage


class PromptAssembler:
    def __init__(self, config: PromptRouterConfig | None = None) -> None:
        self.config = config or PromptRouterConfig.from_file()

    def assemble_messages(
        self,
        prompt_selection: PromptSelection,
        evidence_package: Any | None,
        memory_context: Any | None,
        output_contract: Any,
        scene_state: Any | None = None,
        weak_points: list[str] | None = None,
    ) -> PromptMessageBundle:
        values = {
            "system_boundary": "facts must come from evidence_package only",
            "task_type": prompt_selection.task_type,
            "scene_state": scene_state,
            "rag_evidence": evidence_package,
            "memory_context": memory_context,
            "weak_points": weak_points or [],
            "prompt_asset": (
                prompt_selection.selected_version.content
                if prompt_selection.selected_version is not None
                else prompt_selection.template_id
            ),
            "output_contract": output_contract,
        }
        messages = [
            ModelMessage(
                role="system" if name == "system_boundary" else "user",
                content=self._render_section(name, values[name]),
            )
            for name in self.config.injection_order
        ]
        final_fact_prompt = (
            not prompt_selection.is_clarification
            and "rag_evidence" not in prompt_selection.missing_variables
        )
        return PromptMessageBundle(
            messages=messages,
            injection_order=list(self.config.injection_order),
            template_id=prompt_selection.template_id,
            version=prompt_selection.version,
            snapshot_id=prompt_selection.selected_version.snapshot_id if prompt_selection.selected_version else None,
            injection_summary=[
                {"section": name, "role": message.role, "chars": len(message.content)}
                for name, message in zip(self.config.injection_order, messages, strict=False)
            ],
            missing_variables=list(prompt_selection.missing_variables),
            is_final_fact_prompt=final_fact_prompt,
        )

    def _render_section(self, section: str, value: Any) -> str:
        if value is None:
            rendered = "None"
        else:
            rendered = str(value)
        return f"[{section}]\n{rendered}"


def assemble_messages(prompt_selection: PromptSelection, **kwargs: Any) -> PromptMessageBundle:
    return PromptAssembler().assemble_messages(prompt_selection, **kwargs)
```

## tests/unit/prompts/test_prompt_repository.py

```
import pytest

from prompts.asset_models import PromptStatus, TeachingPromptAsset
from prompts.repository import PromptAssetRepository, PromptRepositoryError


def test_repository_loads_active_asset_and_real_versions():
    repo = PromptAssetRepository.from_config("configs/prompts.yaml")

    asset = repo.get_asset("aviation_fact_qa")
    version = repo.get_active_version("aviation_fact_qa")

    assert asset.status == PromptStatus.ACTIVE
    assert version.version == asset.active_version
    assert "{{rag_evidence}}" in version.content
    assert repo.list_versions("aviation_fact_qa") == ["v1", "v2"]


def test_active_version_requires_approved_evaluation_snapshot():
    repo = PromptAssetRepository.from_config("configs/prompts.yaml")

    snapshot = repo.get_evaluation_snapshot("aviation_fact_qa", "v2")

    assert snapshot.final_decision == "approve"
    assert snapshot.snapshot_id == "aviation_fact_qa_v2"


def test_candidate_asset_is_loadable_but_not_runtime_eligible():
    repo = PromptAssetRepository.from_config("configs/prompts.yaml")

    asset = repo.get_asset("experimental_teaching_strategy")

    assert asset.status == PromptStatus.CANDIDATE
    assert repo.find_for_intent("experimental_teaching_strategy") is None


def test_missing_asset_raises_structured_repository_error():
    repo = PromptAssetRepository.from_config("configs/prompts.yaml")

    with pytest.raises(PromptRepositoryError, match="prompt asset not found"):
        repo.get_asset("missing_prompt")


def test_find_for_intent_returns_runtime_asset():
    repo = PromptAssetRepository.from_config("configs/prompts.yaml")

    asset = repo.find_for_intent("knowledge_explanation")

    assert asset is not None
    assert asset.template_id == "aviation_fact_qa"


def test_runtime_search_skips_candidate_assets_even_if_intent_matches():
    repo = PromptAssetRepository.from_config("configs/prompts.yaml")

    matched_assets = [
        asset
        for asset in (
            repo.find_for_intent("spoken_answer_style"),
            repo.find_for_intent("experimental_teaching_strategy"),
        )
        if isinstance(asset, TeachingPromptAsset)
    ]

    assert all(asset.status in {PromptStatus.ACTIVE, PromptStatus.EXPERIMENTAL} for asset in matched_assets)


def test_runtime_active_version_rejects_mismatched_snapshot_id(tmp_path):
    asset_root = tmp_path / "assets" / "sample_prompt"
    versions_root = asset_root / "versions"
    evaluations_root = asset_root / "evaluations"
    versions_root.mkdir(parents=True)
    evaluations_root.mkdir()

    (asset_root / "asset.json").write_text(
        """
{
  "template_id": "sample_prompt",
  "title": "Sample prompt",
  "task_type": "concept_explanation",
  "status": "active",
  "active_version": "v1",
  "activation_scope": ["concept_explanation"]
}
""".strip(),
        encoding="utf-8",
    )
    (versions_root / "v1.json").write_text(
        """
{
  "template_id": "sample_prompt",
  "version": "v1",
  "content": "Use {{rag_evidence}} only.",
  "review_status": "active",
  "snapshot_id": "sample_prompt_v1_expected",
  "variables": []
}
""".strip(),
        encoding="utf-8",
    )
    (evaluations_root / "sample_prompt_v1.json").write_text(
        """
{
  "snapshot_id": "sample_prompt_v1_actual",
  "template_id": "sample_prompt",
  "version": "v1",
  "final_decision": "approve"
}
""".strip(),
        encoding="utf-8",
    )

    with pytest.raises(PromptRepositoryError, match="runtime active version snapshot mismatch"):
        PromptAssetRepository(tmp_path / "assets")
```

## tests/unit/prompts/test_prompt_assembler.py

```
from core.contracts import EvidenceItem, EvidencePackage, MemoryContext, SceneState
from input.query_understanding import understand_query
from prompts.assembler import PromptAssembler
from prompts.router import PromptRouter
from services.model_client import ModelMessage


def test_prompt_assembler_injects_rag_before_prompt_asset():
    query = understand_query("Explain lift around the wing.")
    evidence = EvidencePackage(
        evidence_items=[EvidenceItem(evidence_id="ev_lift", source_id="src_lift", content="reviewed lift fact")]
    )
    selection = PromptRouter().select_prompt(
        query,
        scene_state=SceneState(component_id="wing"),
        memory_context=MemoryContext(),
        evidence_package=evidence,
        output_contract={"type": "answer_envelope"},
    )

    bundle = PromptAssembler().assemble_messages(
        selection,
        evidence_package=evidence,
        memory_context=MemoryContext(session_preference={"style": "plain"}),
        output_contract={"type": "answer_envelope"},
        scene_state=SceneState(component_id="wing"),
    )

    assert bundle.injection_order.index("rag_evidence") < bundle.injection_order.index("prompt_asset")
    assert bundle.is_final_fact_prompt is True
    assert all(isinstance(message, ModelMessage) for message in bundle.messages)
    assert {message.role for message in bundle.messages} <= {"system", "user"}
    assert "{{rag_evidence}}" in bundle.messages[bundle.injection_order.index("prompt_asset")].content
    assert bundle.template_id == selection.template_id
    assert bundle.version == selection.version


def test_missing_rag_evidence_bundle_is_not_final_fact_prompt():
    query = understand_query("Explain lift.")
    selection = PromptRouter().select_prompt(
        query,
        scene_state=SceneState(),
        output_contract={"type": "answer_envelope"},
    )

    bundle = PromptAssembler().assemble_messages(
        selection,
        evidence_package=None,
        memory_context=None,
        output_contract={"type": "answer_envelope"},
        scene_state=SceneState(),
    )

    assert bundle.template_id == "aviation_basic_safe"
    assert bundle.is_final_fact_prompt is False
    assert bundle.missing_variables == ["rag_evidence"]
```
