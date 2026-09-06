from __future__ import annotations

import argparse
from importlib import metadata
import json
import os
from pathlib import Path
import sys
import tomllib
from collections.abc import Callable
from typing import Any

PROJECT_ROOT = Path(__file__).resolve().parents[1]
SRC_DIR = PROJECT_ROOT / "src"
if str(SRC_DIR) not in sys.path:
    sys.path.insert(0, str(SRC_DIR))

from core.runtime_settings import RuntimeSettings
from core.settings import load_settings
from core.simple_yaml import parse_simple_yaml
from knowledge.config import load_rag_config
from memory.controller import MemoryController
from voice.providers import ProviderRegistry
from voice.settings import VoiceSettings, load_string_mapping, load_terminology_mapping


REQUIRED_FILES = (
    "configs/app.yaml",
    "configs/providers.yaml",
    "configs/rag.yaml",
    "configs/prompts.yaml",
    "configs/memory.yaml",
    "configs/voice.yaml",
    "configs/voice_terminology.yaml",
    "configs/voice_pronunciation.yaml",
    "configs/evals.yaml",
    "pyproject.toml",
)
REQUIRED_WEBSOCKETS_VERSION = "15.0.1"
REQUIRED_WEBSOCKETS_DEPENDENCY = f"websockets=={REQUIRED_WEBSOCKETS_VERSION}"
REQUIRED_LANGGRAPH_VERSION = "1.2.9"
REQUIRED_LANGGRAPH_CHECKPOINTER_VERSION = "3.1.0"
WebSocketsRuntimeLoader = Callable[[], tuple[str | None, object, object]]
LangGraphRuntimeLoader = Callable[[], tuple[object | None, object | None]]

# ---------------------------------------------------------------------------
# real profile 口径
# ---------------------------------------------------------------------------
# 密钥环境变量名以 configs/providers.yaml 各真实 provider 的 api_key_env 为准；
# 配置解析失败时回退到下述文档化名称（providers.yaml 注释与 .env.example 一致）。
_REAL_PROVIDER_KEY_PATHS = {
    "deepseek": "providers.llm.deepseek.api_key_env",
    "qwen": "providers.embedding.qwen.api_key_env",
    "nvidia": "providers.embedding.nvidia.api_key_env",
}
_REAL_PROVIDER_KEY_FALLBACKS = {
    "deepseek": "DEEPSEEK_API_KEY",
    "qwen": "DASHSCOPE_API_KEY",
    "nvidia": "NVIDIA_API_KEY",
}
# 真实 profile 的 ASR/TTS 可达性探测超时（秒）。
REAL_VOICE_PROBE_TIMEOUT_SECONDS = 5.0


def _declared_dependencies(path: Path) -> tuple[str, ...]:
    with path.open("rb") as handle:
        payload = tomllib.load(handle)
    project = payload.get("project")
    if not isinstance(project, dict):
        return ()
    dependencies = project.get("dependencies")
    if not isinstance(dependencies, list) or any(
        not isinstance(item, str) for item in dependencies
    ):
        return ()
    return tuple(dependencies)


def _load_websockets_runtime() -> tuple[str | None, object, object]:
    import websockets
    from websockets.asyncio.client import connect
    from websockets.asyncio.server import serve

    version = getattr(websockets, "__version__", None)
    return version if isinstance(version, str) else None, serve, connect


def _load_langgraph_runtime() -> tuple[object, object]:
    from langgraph.checkpoint.sqlite import SqliteSaver
    from langgraph.graph import StateGraph

    return StateGraph, SqliteSaver


def _distribution_version(name: str) -> str | None:
    try:
        return metadata.version(name)
    except metadata.PackageNotFoundError:
        return None


def _resolved_configured_path(root: Path, value: str | Path) -> Path:
    candidate = Path(value).expanduser()
    if not candidate.is_absolute():
        candidate = root / candidate
    return candidate.resolve()


def _memory_database_path(config_path: Path, root: Path) -> Path:
    # MemoryController.from_config() initializes SQLite, so deployment validation
    # only reuses its configuration parser and never constructs a controller.
    payload = MemoryController._load_yaml_subset(config_path)
    memory = payload.get("memory")
    if not isinstance(memory, dict):
        raise ValueError("memory config is missing the memory mapping")
    store = memory.get("store")
    if not isinstance(store, dict):
        raise ValueError("memory config is missing the memory.store mapping")
    raw_path = store.get("path")
    if not isinstance(raw_path, str) or not raw_path.strip():
        raise ValueError("memory config is missing memory.store.path")
    return _resolved_configured_path(root, raw_path)


def _path_key(path: Path) -> str:
    return str(path.resolve()).casefold()


def _checkpoint_path_classification(
    checkpoint_path: Path,
    *,
    rag_database_path: Path,
    memory_database_path: Path,
) -> str:
    checkpoint_key = _path_key(checkpoint_path)
    if checkpoint_key == _path_key(rag_database_path):
        return "conflicts_with_knowledge"
    if checkpoint_key == _path_key(memory_database_path):
        return "conflicts_with_memory"
    return "separate"


def _validate_mock_profile(
    *,
    root: Path,
    registry: ProviderRegistry | None = None,
    websockets_version: str | None = None,
    websockets_runtime_loader: WebSocketsRuntimeLoader | None = None,
    langgraph_runtime_loader: LangGraphRuntimeLoader | None = None,
) -> dict[str, Any]:
    """Validate the offline realtime voice capability without external services."""

    missing_files = [path for path in REQUIRED_FILES if not (root / path).is_file()]
    failed_checks: list[str] = []
    if missing_files:
        failed_checks.append("required_files")

    provider_profile: str | None = None
    embedding_provider_profile: str | None = None
    runtime_settings: RuntimeSettings | None = None
    try:
        app_settings = load_settings(root / "configs", env={})
        runtime_settings = RuntimeSettings.from_settings(app_settings)
        provider_profile = app_settings.get("providers.llm.default")
        embedding_provider_profile = app_settings.get("providers.embedding.default")
        # R0/T1-3: mock 门禁的 provider 口径从 configs/mock/providers.yaml 覆盖读取，
        # 使离线校验与真实默认 provider（deepseek / bge_m3）解耦。
        mock_providers_path = root / "configs" / "mock" / "providers.yaml"
        if mock_providers_path.is_file():
            mock_providers = parse_simple_yaml(
                mock_providers_path.read_text(encoding="utf-8")
            )
            provider_profile = mock_providers.get("providers", {}).get("llm", {}).get(
                "default", provider_profile
            )
            embedding_provider_profile = mock_providers.get("providers", {}).get(
                "embedding", {}
            ).get("default", embedding_provider_profile)
    except Exception:
        failed_checks.append("app_settings")
    if provider_profile != "mock" or embedding_provider_profile != "mock":
        failed_checks.append("offline_provider_profile")

    resolved_registry = registry or ProviderRegistry.with_default_providers()
    for relative_path, check_name, loader in (
        (
            "configs/voice_terminology.yaml",
            "terminology_lexicon",
            lambda path: (
                load_terminology_mapping(path, root_key="terms"),
                load_terminology_mapping(path, root_key="simplifications"),
            ),
        ),
        ("configs/voice_pronunciation.yaml", "pronunciation_lexicon", load_string_mapping),
    ):
        try:
            mapping = loader(root / relative_path)
            if not mapping or (isinstance(mapping, tuple) and any(not item for item in mapping)):
                raise ValueError("lexicon is empty")
        except Exception:
            failed_checks.append(check_name)

    voice_settings: VoiceSettings | None = None
    # R0/T1-3: mock 门禁的语音口径从 configs/mock/voice.yaml 覆盖读取（ASR/TTS 走 mock）。
    mock_voice_path = root / "configs" / "mock" / "voice.yaml"
    voice_path = mock_voice_path if mock_voice_path.is_file() else root / "configs" / "voice.yaml"
    if voice_path.is_file():
        try:
            voice_settings = VoiceSettings.from_file(
                voice_path,
                known_providers=resolved_registry.known_providers,
            )
        except Exception:
            failed_checks.append("voice_config")
    elif "required_files" not in failed_checks:
        failed_checks.append("voice_config")

    dependency_declared = False
    pyproject_path = root / "pyproject.toml"
    if pyproject_path.is_file():
        try:
            dependency_declared = (
                REQUIRED_WEBSOCKETS_DEPENDENCY in _declared_dependencies(pyproject_path)
            )
        except Exception:
            dependency_declared = False
    if not dependency_declared:
        failed_checks.append("pyproject_dependency")

    providers = {
        "transport": voice_settings.transport if voice_settings is not None else None,
        "vad": voice_settings.vad_provider if voice_settings is not None else None,
        "asr": voice_settings.asr_provider if voice_settings is not None else None,
        "tts": voice_settings.tts_provider if voice_settings is not None else None,
    }
    if voice_settings is not None:
        expected = {
            "transport": "websocket",
            "vad": "mock",
            "asr": "mock",
            "tts": "mock",
        }
        if providers != expected:
            failed_checks.append("voice_provider_profile")
        if voice_settings.raw_audio_persist_enabled is not False:
            failed_checks.append("voice_privacy")
        expected_terminology = (root / "configs" / "voice_terminology.yaml").resolve()
        expected_pronunciation = (root / "configs" / "voice_pronunciation.yaml").resolve()
        if (
            voice_settings.terminology_lexicon_path != expected_terminology
            or voice_settings.pronunciation_lexicon_path != expected_pronunciation
        ):
            failed_checks.append("voice_lexicon_binding")

    distribution_version = websockets_version
    if distribution_version is None:
        try:
            distribution_version = metadata.version("websockets")
        except Exception:
            distribution_version = None
    if distribution_version != REQUIRED_WEBSOCKETS_VERSION:
        failed_checks.append("websockets_version")

    runtime_version: str | None = None
    runtime_serve: object = None
    runtime_connect: object = None
    try:
        runtime_version, runtime_serve, runtime_connect = (
            websockets_runtime_loader or _load_websockets_runtime
        )()
    except Exception:
        pass
    if runtime_version != REQUIRED_WEBSOCKETS_VERSION:
        failed_checks.append("websockets_runtime")
    if not callable(runtime_serve) or not callable(runtime_connect):
        failed_checks.append("websockets_api")

    langgraph_version = _distribution_version("langgraph")
    langgraph_checkpointer_version = _distribution_version(
        "langgraph-checkpoint-sqlite"
    )
    state_graph: object | None = None
    sqlite_saver: object | None = None
    try:
        state_graph, sqlite_saver = (
            langgraph_runtime_loader or _load_langgraph_runtime
        )()
    except Exception:
        pass
    langgraph_runtime = "ok"
    if not callable(state_graph) or langgraph_version != REQUIRED_LANGGRAPH_VERSION:
        langgraph_runtime = "unavailable"
        failed_checks.append("langgraph_runtime")
    langgraph_checkpointer = "sqlite"
    if (
        not callable(sqlite_saver)
        or langgraph_checkpointer_version != REQUIRED_LANGGRAPH_CHECKPOINTER_VERSION
    ):
        langgraph_checkpointer = "unavailable"
        failed_checks.append("langgraph_checkpointer")

    langgraph_checkpoint_path_is_separate = False
    langgraph_checkpoint_path_classification = "unavailable"
    if runtime_settings is not None:
        try:
            rag_config = load_rag_config(root / "configs" / "rag.yaml")
            checkpoint_path = _resolved_configured_path(
                root,
                runtime_settings.langgraph.checkpoint_path,
            )
            rag_database_path = _resolved_configured_path(
                root,
                rag_config.repository.path,
            )
            memory_database_path = _memory_database_path(
                root / "configs" / "memory.yaml",
                root,
            )
            langgraph_checkpoint_path_classification = _checkpoint_path_classification(
                checkpoint_path,
                rag_database_path=rag_database_path,
                memory_database_path=memory_database_path,
            )
            langgraph_checkpoint_path_is_separate = (
                langgraph_checkpoint_path_classification == "separate"
            )
            if not langgraph_checkpoint_path_is_separate:
                failed_checks.append("langgraph_checkpoint_path_conflict")
        except Exception:
            failed_checks.append("langgraph_checkpoint_path")
    else:
        failed_checks.append("langgraph_checkpoint_path")

    failed_checks = list(dict.fromkeys(failed_checks))
    realtime_mock_ready = not failed_checks
    return {
        "status": "ok" if realtime_mock_ready else "failed",
        "profile": "mock",
        "missing_file_count": len(missing_files),
        "failed_checks": failed_checks,
        "provider_profile": provider_profile,
        "embedding_provider_profile": embedding_provider_profile,
        "mock_offline": (
            provider_profile == "mock" and embedding_provider_profile == "mock"
        ),
        "voice_transport": providers["transport"],
        "voice_providers": {
            "vad": providers["vad"],
            "asr": providers["asr"],
            "tts": providers["tts"],
        },
        "raw_audio_persist_enabled": (
            voice_settings.raw_audio_persist_enabled
            if voice_settings is not None
            else None
        ),
        "realtime_mock_ready": realtime_mock_ready,
        "websockets_version": runtime_version,
        "websockets_distribution_version": distribution_version,
        "websockets_dependency_declared": dependency_declared,
        "langgraph_runtime": langgraph_runtime,
        "langgraph_version": langgraph_version,
        "langgraph_checkpointer": langgraph_checkpointer,
        "langgraph_checkpointer_version": langgraph_checkpointer_version,
        "langgraph_checkpoint_path_is_separate": langgraph_checkpoint_path_is_separate,
        "langgraph_checkpoint_path_classification": (
            langgraph_checkpoint_path_classification
        ),
    }


def _hf_hub_model_dir(model_id: str) -> Path:
    owner, name = model_id.split("/", 1)
    return Path.home() / ".cache" / "huggingface" / "hub" / f"models--{owner}--{name}"


def _hf_model_is_cached(model_id: str) -> bool:
    model_dir = _hf_hub_model_dir(model_id)
    snapshots = model_dir / "snapshots"
    return model_dir.is_dir() and snapshots.is_dir() and any(snapshots.iterdir())


def _real_env_key_names(app_settings: Any) -> tuple[str, ...]:
    if app_settings is None:
        return ("DEEPSEEK_API_KEY",)

    selected_profiles = (
        app_settings.get("providers.llm.default"),
        app_settings.get("providers.embedding.default"),
    )
    names: list[str] = []
    for profile in selected_profiles:
        if not isinstance(profile, str):
            continue
        config_path = _REAL_PROVIDER_KEY_PATHS.get(profile)
        if config_path is None:
            continue
        configured = app_settings.get(config_path)
        name = (
            configured.strip()
            if isinstance(configured, str) and configured.strip()
            else _REAL_PROVIDER_KEY_FALLBACKS[profile]
        )
        if name not in names:
            names.append(name)
    return tuple(names)


def _probe_whisper_model(model_size: str, timeout: float) -> dict[str, str]:
    """Probe faster-whisper model loadability with a bounded timeout."""

    import asyncio

    def _load() -> None:
        from faster_whisper import WhisperModel

        WhisperModel(model_size, device="cpu", compute_type="int8")

    try:
        asyncio.run(asyncio.wait_for(asyncio.to_thread(_load), timeout=timeout))
    except TimeoutError:
        return {
            "status": "unreachable",
            "detail": f"model_load_timeout_{int(timeout)}s",
        }
    except Exception as exc:
        return {"status": "unreachable", "detail": f"{type(exc).__name__}: {exc}"}
    return {"status": "ok", "detail": "model_loaded"}


def _probe_edge_tts(voice: str, timeout: float) -> dict[str, str]:
    """Probe edge-tts public endpoint reachability with a bounded timeout."""

    import asyncio

    async def _synthesize() -> None:
        import edge_tts

        communicate = edge_tts.Communicate("可达性探测。", voice=voice)
        async for _chunk in communicate.stream():
            pass

    try:
        asyncio.run(asyncio.wait_for(_synthesize(), timeout=timeout))
    except TimeoutError:
        return {
            "status": "unreachable",
            "detail": f"synthesize_timeout_{int(timeout)}s",
        }
    except Exception as exc:
        return {"status": "unreachable", "detail": f"{type(exc).__name__}: {exc}"}
    return {"status": "ok", "detail": "synthesized"}


def _validate_real_profile(*, root: Path) -> dict[str, Any]:
    """Validate real-provider deployment readiness without sending business data.

    检查项：必需密钥环境变量存在性、provider 配置为真实值、模型路径存在性、
    ASR/TTS provider 可达性探测（带 5s 超时）。密钥缺失时仍完成其余检查，
    但不会伪装为真实连通；探测失败一律降级为 unreachable 并在报告中列明。
    """

    failed_checks: list[str] = []
    missing_files = [path for path in REQUIRED_FILES if not (root / path).is_file()]
    if missing_files:
        failed_checks.append("required_files")

    app_settings: Any = None
    provider_profile: str | None = None
    embedding_provider_profile: str | None = None
    try:
        app_settings = load_settings(root / "configs", env={})
        provider_profile = app_settings.get("providers.llm.default")
        embedding_provider_profile = app_settings.get("providers.embedding.default")
    except Exception:
        failed_checks.append("app_settings")

    env_key_names = _real_env_key_names(app_settings)
    missing_env_keys = sorted(
        name for name in env_key_names if not os.environ.get(name, "").strip()
    )
    if missing_env_keys:
        failed_checks.append("real_env_keys_missing")

    resolved_registry = ProviderRegistry.with_default_providers()
    voice_settings: VoiceSettings | None = None
    voice_path = root / "configs" / "voice.yaml"
    if voice_path.is_file():
        try:
            voice_settings = VoiceSettings.from_file(
                voice_path,
                known_providers=resolved_registry.known_providers,
            )
        except Exception:
            failed_checks.append("voice_config")
    elif "required_files" not in failed_checks:
        failed_checks.append("voice_config")

    voice_providers = {
        "vad": voice_settings.vad_provider if voice_settings is not None else None,
        "asr": voice_settings.asr_provider if voice_settings is not None else None,
        "tts": voice_settings.tts_provider if voice_settings is not None else None,
    }
    if (
        provider_profile in (None, "mock")
        or embedding_provider_profile in (None, "mock")
        or voice_providers["asr"] != "whisper"
        or voice_providers["tts"] != "edge"
    ):
        failed_checks.append("real_provider_config")

    whisper_model_size = "small"
    tts_voice = "zh-CN-XiaoxiaoNeural"
    if voice_settings is not None:
        whisper_model_size = str(
            voice_settings.asr_config.get("model_size") or whisper_model_size
        )
        tts_voice = str(voice_settings.tts_config.get("voice") or tts_voice)

    model_paths = {
        f"whisper_{whisper_model_size}": _hf_model_is_cached(
            f"Systran/faster-whisper-{whisper_model_size}"
        ),
        "bge_m3": _hf_model_is_cached("BAAI/bge-m3"),
    }
    if not all(model_paths.values()):
        failed_checks.append("real_model_paths")

    voice_reachability = {
        "asr": _probe_whisper_model(
            whisper_model_size, REAL_VOICE_PROBE_TIMEOUT_SECONDS
        ),
        "tts": _probe_edge_tts(tts_voice, REAL_VOICE_PROBE_TIMEOUT_SECONDS),
    }
    if any(item["status"] != "ok" for item in voice_reachability.values()):
        failed_checks.append("real_voice_reachability")

    failed_checks = list(dict.fromkeys(failed_checks))
    return {
        "status": "ok" if not failed_checks else "failed",
        "profile": "real",
        "missing_file_count": len(missing_files),
        "failed_checks": failed_checks,
        "missing_env_keys": missing_env_keys,
        "provider_profile": provider_profile,
        "embedding_provider_profile": embedding_provider_profile,
        "voice_providers": voice_providers,
        "model_paths": model_paths,
        "voice_reachability": voice_reachability,
        "real_ready": not failed_checks,
    }


def validate(
    *,
    project_root: str | Path = PROJECT_ROOT,
    profile: str = "mock",
    registry: ProviderRegistry | None = None,
    websockets_version: str | None = None,
    websockets_runtime_loader: WebSocketsRuntimeLoader | None = None,
    langgraph_runtime_loader: LangGraphRuntimeLoader | None = None,
) -> dict[str, Any]:
    """Validate a deployment profile.

    ``mock`` profile checks the offline realtime voice capability without
    external services; ``real`` profile checks real-provider deployment
    readiness (env keys, real provider config, model paths, ASR/TTS
    reachability probes with a 5s timeout).
    """
    if profile not in ("mock", "real"):
        raise ValueError(f"unsupported profile: {profile!r}")
    root = Path(project_root).resolve()
    if profile == "real":
        return _validate_real_profile(root=root)
    return _validate_mock_profile(
        root=root,
        registry=registry,
        websockets_version=websockets_version,
        websockets_runtime_loader=websockets_runtime_loader,
        langgraph_runtime_loader=langgraph_runtime_loader,
    )


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Validate the deployment profile (mock = offline capability; "
        "real = real-provider readiness)."
    )
    parser.add_argument("--project-root", type=Path, default=PROJECT_ROOT)
    parser.add_argument(
        "--profile",
        choices=("mock", "real"),
        default="mock",
        help="deployment profile to validate (default: mock)",
    )
    args = parser.parse_args(argv)
    report = validate(project_root=args.project_root, profile=args.profile)
    print(json.dumps(report, ensure_ascii=True, indent=2))
    return 0 if report["status"] == "ok" else 1


if __name__ == "__main__":
    raise SystemExit(main())
