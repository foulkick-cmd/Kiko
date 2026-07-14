"""Backend-agnostic storage contract.

The interface is deliberately narrow and speaks in domain objects
(:class:`~deal_tracker.models.Product`,
:class:`~deal_tracker.models.PriceObservation`). Both the SQLite and PostgreSQL
implementations share the *same* schema and SQL shape (only the paramstyle and
connection differ), so swapping backends is a config change, not a rewrite.
"""

from __future__ import annotations

import abc
from dataclasses import dataclass
from datetime import datetime

from ..models import PriceObservation, Product, utcnow


@dataclass(slots=True)
class ScrapeRun:
    """Metadata about a single scrape execution (row in ``scrape_runs``)."""

    run_id: int | None = None
    store: str = ""
    started_at: datetime | None = None
    finished_at: datetime | None = None
    products_seen: int = 0
    products_stored: int = 0
    status: str = "running"    # running | success | partial | failed
    note: str | None = None


class Storage(abc.ABC):
    """Abstract persistence backend."""

    # --- lifecycle ----------------------------------------------------- #
    @abc.abstractmethod
    def initialize(self) -> None:
        """Create tables and indexes if they do not already exist."""

    @abc.abstractmethod
    def close(self) -> None:
        ...

    def __enter__(self) -> "Storage":
        self.initialize()
        return self

    def __exit__(self, *exc: object) -> None:
        self.close()

    # --- scrape runs --------------------------------------------------- #
    @abc.abstractmethod
    def start_run(self, store: str) -> ScrapeRun:
        """Insert a new ``running`` scrape-run row and return it."""

    @abc.abstractmethod
    def finish_run(self, run: ScrapeRun) -> None:
        """Persist final counts/status for a run."""

    # --- products & history ------------------------------------------- #
    @abc.abstractmethod
    def upsert_product(self, product: Product) -> None:
        """Insert or update the descriptive ``products`` row."""

    @abc.abstractmethod
    def append_price(self, product: Product, run_id: int | None = None) -> None:
        """Append an immutable snapshot to ``price_history`` (never overwrite)."""

    @abc.abstractmethod
    def last_price(self, product_id: str) -> float | None:
        """Return the most recently recorded ``current_price`` for a product."""

    @abc.abstractmethod
    def price_history(
        self, product_id: str, *, since: datetime | None = None
    ) -> list[PriceObservation]:
        """Return the price history for a product, oldest first."""

    @abc.abstractmethod
    def all_product_ids(self) -> list[str]:
        ...

    @abc.abstractmethod
    def get_product(self, product_id: str) -> Product | None:
        ...

    # --- convenience --------------------------------------------------- #
    def record(self, product: Product, run_id: int | None = None) -> None:
        """Upsert descriptive fields and append a price snapshot in one call."""
        self.upsert_product(product)
        self.append_price(product, run_id=run_id)

    @staticmethod
    def _now() -> datetime:
        return utcnow()
