"""Configuration loading.

Configuration is resolved with the following precedence (highest wins):

1. Environment variables (optionally sourced from a ``.env`` file).
2. A YAML config file (``config.yaml`` by default, or ``$KIKO_CONFIG``).
3. Built-in defaults defined here.

Every value is documented in ``config.example.yaml``.
"""

from __future__ import annotations

import logging
import os
from dataclasses import dataclass, field, fields
from pathlib import Path
from typing import Any

import yaml
from dotenv import load_dotenv

logger = logging.getLogger(__name__)


# --------------------------------------------------------------------------- #
# Sub-configs
# --------------------------------------------------------------------------- #
@dataclass(slots=True)
class ScrapeConfig:
    # Category / deal pages to crawl. URLs are store-specific.
    category_urls: list[str] = field(default_factory=list)
    max_products_per_category: int = 60
    min_delay_seconds: float = 2.0
    max_delay_seconds: float = 3.0
    request_timeout: float = 20.0
    max_retries: int = 4
    respect_robots: bool = True
    user_agent: str = (
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
        "(KHTML, like Gecko) Chrome/125.0 Safari/537.36 KikoDealTracker/0.1"
    )
    # When Requests-only data is incomplete, escalate to Playwright.
    allow_playwright: bool = True
    # If we detect blocking (429/403/challenge), back off this many seconds.
    block_backoff_seconds: float = 900.0


@dataclass(slots=True)
class StorageConfig:
    backend: str = "sqlite"  # "sqlite" | "postgres"
    sqlite_path: str = "data/kiko.db"
    # Used only when backend == "postgres".
    postgres_dsn: str | None = None
    history_window_days: int = 90


@dataclass(slots=True)
class AnalyticsConfig:
    window_days: int = 90
    stability_band_pct: float = 5.0        # ±5 % counts as "stable"
    stability_min_days: int = 14           # min stable days before a drop
    stability_max_days: int = 30
    sudden_drop_pct: float = 15.0          # >=15 % drop
    sudden_drop_window_days: int = 3       # …within 1-3 days
    min_history_days: int = 30             # below this -> history_insufficient
    real_discount_tolerance: float = 1.02  # current <= min_90d * 1.02


@dataclass(slots=True)
class RankingConfig:
    weight_discount: float = 0.4
    weight_history_confidence: float = 0.3
    weight_rating: float = 0.2
    weight_seller: float = 0.1
    seller_score_noon: float = 1.0
    seller_score_marketplace: float = 0.5
    # Optional filters applied to the ranked output.
    min_discount_percent: float | None = None
    min_rating: float | None = None
    seller_type: str | None = None          # "Noon" | "Marketplace"
    min_confidence: str | None = None        # "High" | "Medium" | "Low"


@dataclass(slots=True)
class AlertConfig:
    enabled: bool = False
    telegram_bot_token: str | None = None
    telegram_chat_id: str | None = None
    # Only alert when the drop is at least this large vs the last record.
    min_drop_percent: float = 0.0
    attach_chart: bool = True
    # Only alert on these confidence labels (empty -> all).
    confidence_allowlist: list[str] = field(default_factory=list)
    # Persisted duplicate-alert suppression state.
    state_path: str = "data/alert_state.json"


@dataclass(slots=True)
class OutputConfig:
    csv_path: str = "output/deals.csv"
    charts_dir: str = "output/charts"
    log_dir: str = "logs"
    log_level: str = "INFO"


@dataclass(slots=True)
class Config:
    scrape: ScrapeConfig = field(default_factory=ScrapeConfig)
    storage: StorageConfig = field(default_factory=StorageConfig)
    analytics: AnalyticsConfig = field(default_factory=AnalyticsConfig)
    ranking: RankingConfig = field(default_factory=RankingConfig)
    alerts: AlertConfig = field(default_factory=AlertConfig)
    output: OutputConfig = field(default_factory=OutputConfig)


# --------------------------------------------------------------------------- #
# Loading
# --------------------------------------------------------------------------- #
def _apply_dict(obj: Any, data: dict[str, Any]) -> None:
    """Populate a dataclass instance in-place from a plain dict (shallow)."""
    valid = {f.name for f in fields(obj)}
    for key, value in data.items():
        if key in valid:
            setattr(obj, key, value)
        else:
            logger.warning("Ignoring unknown config key %r", key)


def _apply_env(cfg: Config) -> None:
    """Overlay a curated set of environment variables onto the config."""
    env = os.environ

    def _f(name: str) -> float | None:
        v = env.get(name)
        return float(v) if v not in (None, "") else None

    if v := env.get("KIKO_STORAGE_BACKEND"):
        cfg.storage.backend = v
    if v := env.get("KIKO_SQLITE_PATH"):
        cfg.storage.sqlite_path = v
    if v := env.get("KIKO_POSTGRES_DSN"):
        cfg.storage.postgres_dsn = v
    if v := env.get("TELEGRAM_BOT_TOKEN"):
        cfg.alerts.telegram_bot_token = v
        cfg.alerts.enabled = True
    if v := env.get("TELEGRAM_CHAT_ID"):
        cfg.alerts.telegram_chat_id = v
    if v := env.get("KIKO_LOG_LEVEL"):
        cfg.output.log_level = v
    if (v := _f("KIKO_MIN_DELAY")) is not None:
        cfg.scrape.min_delay_seconds = v
    if (v := _f("KIKO_MAX_DELAY")) is not None:
        cfg.scrape.max_delay_seconds = v
    if v := env.get("KIKO_CATEGORY_URLS"):
        cfg.scrape.category_urls = [u.strip() for u in v.split(",") if u.strip()]


def load_config(path: str | os.PathLike[str] | None = None) -> Config:
    """Load configuration from YAML + environment.

    Args:
        path: Explicit path to a YAML file. Falls back to ``$KIKO_CONFIG`` and
            then to ``config.yaml`` in the current directory. A missing file is
            not an error — built-in defaults are used.
    """
    load_dotenv(override=False)  # populate os.environ from .env if present

    cfg = Config()
    yaml_path = Path(path or os.environ.get("KIKO_CONFIG", "config.yaml"))
    if yaml_path.exists():
        logger.debug("Loading config from %s", yaml_path)
        raw = yaml.safe_load(yaml_path.read_text(encoding="utf-8")) or {}
        _apply_dict(cfg.scrape, raw.get("scrape", {}))
        _apply_dict(cfg.storage, raw.get("storage", {}))
        _apply_dict(cfg.analytics, raw.get("analytics", {}))
        _apply_dict(cfg.ranking, raw.get("ranking", {}))
        _apply_dict(cfg.alerts, raw.get("alerts", {}))
        _apply_dict(cfg.output, raw.get("output", {}))
    else:
        logger.info("No config file at %s — using defaults + environment.", yaml_path)

    _apply_env(cfg)
    return cfg
