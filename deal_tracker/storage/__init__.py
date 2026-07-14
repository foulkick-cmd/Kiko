"""Persistence layer.

:class:`Storage` defines the backend-agnostic contract. :class:`SqliteStorage`
is the default; :class:`PostgresStorage` is a drop-in replacement requiring only
a DSN. See :func:`get_storage`.
"""

from __future__ import annotations

from ..config import StorageConfig
from .base import ScrapeRun, Storage
from .sqlite_storage import SqliteStorage

__all__ = ["Storage", "ScrapeRun", "SqliteStorage", "get_storage"]


def get_storage(config: StorageConfig) -> Storage:
    """Instantiate the configured storage backend."""
    backend = config.backend.lower()
    if backend == "sqlite":
        return SqliteStorage(config.sqlite_path, window_days=config.history_window_days)
    if backend in ("postgres", "postgresql"):
        from .postgres_storage import PostgresStorage

        if not config.postgres_dsn:
            raise ValueError("storage.postgres_dsn is required for the postgres backend")
        return PostgresStorage(config.postgres_dsn, window_days=config.history_window_days)
    raise ValueError(f"Unknown storage backend: {config.backend!r}")
