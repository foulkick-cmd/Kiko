"""Store adapters.

A :class:`StoreAdapter` encapsulates everything store-specific: how to list
products from category pages, how to parse a product, and its base URL. Adding a
new store (Amazon, Jarir, Extra, Carrefour, …) means implementing one adapter —
the fetchers, storage, analytics, charts and alerts are all store-agnostic.
"""

from __future__ import annotations

from .base import StoreAdapter
from .noon import NoonAdapter

_REGISTRY: dict[str, type[StoreAdapter]] = {
    "noon_sa": NoonAdapter,
}


def get_adapter(store_key: str) -> StoreAdapter:
    """Instantiate a registered adapter by key (e.g. ``"noon_sa"``)."""
    try:
        return _REGISTRY[store_key]()
    except KeyError as exc:
        raise ValueError(
            f"Unknown store {store_key!r}. Known: {sorted(_REGISTRY)}"
        ) from exc


def register_adapter(store_key: str, adapter_cls: type[StoreAdapter]) -> None:
    """Register a new store adapter (used by extensions/plugins)."""
    _REGISTRY[store_key] = adapter_cls


__all__ = ["StoreAdapter", "NoonAdapter", "get_adapter", "register_adapter"]
