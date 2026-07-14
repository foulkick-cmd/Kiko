"""PostgreSQL storage backend.

A drop-in replacement for :class:`SqliteStorage`. It shares the exact same
schema and SQL (see :mod:`deal_tracker.storage._schema`); only the connection,
paramstyle (``%s``) and timestamp handling differ. Requires ``psycopg`` (v3),
installed via the ``postgres`` extra.

Because timestamps are stored as native ``TIMESTAMPTZ`` here (rather than ISO
strings as in SQLite), history is fully queryable with native SQL as data grows.
"""

from __future__ import annotations

import logging
from datetime import datetime, timedelta, timezone

from ..models import PriceObservation, Product, SellerType, utcnow
from . import _schema
from .base import ScrapeRun, Storage

logger = logging.getLogger(__name__)

try:
    import psycopg
except ImportError:  # pragma: no cover
    psycopg = None  # type: ignore[assignment]


def _q(sql: str) -> str:
    """Rewrite ``{p}`` placeholders to psycopg's ``%s`` paramstyle."""
    return sql.replace("{p}", "%s")


class PostgresStorage(Storage):
    """Store snapshots in a PostgreSQL database."""

    def __init__(self, dsn: str, *, window_days: int = 90) -> None:
        if psycopg is None:
            raise RuntimeError(
                "psycopg is not installed. Install with `pip install 'psycopg[binary]'`."
            )
        self.dsn = dsn
        self.window_days = window_days
        self._conn = None

    @property
    def conn(self):
        if self._conn is None or self._conn.closed:
            self._conn = psycopg.connect(self.dsn)
        return self._conn

    def initialize(self) -> None:
        with self.conn.cursor() as cur:
            for stmt in _schema.ddl(
                autoincrement_pk="BIGSERIAL PRIMARY KEY",
                timestamp_type="TIMESTAMPTZ",
            ):
                cur.execute(stmt)
        self.conn.commit()
        logger.debug("PostgreSQL schema initialised")

    def close(self) -> None:
        if self._conn is not None and not self._conn.closed:
            self._conn.commit()
            self._conn.close()

    # --- scrape runs --------------------------------------------------- #
    def start_run(self, store: str) -> ScrapeRun:
        started = utcnow()
        with self.conn.cursor() as cur:
            cur.execute(
                _q(_schema.START_RUN) + " RETURNING run_id", (store, started)
            )
            run_id = cur.fetchone()[0]
        self.conn.commit()
        return ScrapeRun(run_id=run_id, store=store, started_at=started)

    def finish_run(self, run: ScrapeRun) -> None:
        run.finished_at = run.finished_at or utcnow()
        with self.conn.cursor() as cur:
            cur.execute(
                _q(_schema.FINISH_RUN),
                (
                    run.finished_at,
                    run.products_seen,
                    run.products_stored,
                    run.status,
                    run.note,
                    run.run_id,
                ),
            )
        self.conn.commit()

    # --- products & history ------------------------------------------- #
    def upsert_product(self, product: Product) -> None:
        now = utcnow()
        with self.conn.cursor() as cur:
            cur.execute(
                _q(_schema.UPSERT_PRODUCT),
                (
                    product.product_id,
                    product.store,
                    product.title,
                    product.seller_name,
                    product.seller_type.value,
                    product.product_image,
                    product.product_url,
                    product.category,
                    product.currency,
                    now,
                    now,
                ),
            )
        self.conn.commit()

    def append_price(self, product: Product, run_id: int | None = None) -> None:
        with self.conn.cursor() as cur:
            cur.execute(
                _q(_schema.APPEND_PRICE),
                (
                    product.product_id,
                    run_id,
                    product.seller_name,
                    product.current_price,
                    product.before_price,
                    product.displayed_discount_percent,
                    product.timestamp,
                ),
            )
        self.conn.commit()

    def last_price(self, product_id: str) -> float | None:
        with self.conn.cursor() as cur:
            cur.execute(_q(_schema.LAST_PRICE), (product_id,))
            row = cur.fetchone()
        return float(row[0]) if row and row[0] is not None else None

    def price_history(
        self, product_id: str, *, since: datetime | None = None
    ) -> list[PriceObservation]:
        if since is None:
            since = utcnow() - timedelta(days=self.window_days)
        with self.conn.cursor() as cur:
            cur.execute(_q(_schema.PRICE_HISTORY), (product_id, since))
            rows = cur.fetchall()
        return [self._row_to_obs(r) for r in rows]

    def all_product_ids(self) -> list[str]:
        with self.conn.cursor() as cur:
            cur.execute(_schema.ALL_PRODUCT_IDS)
            return [r[0] for r in cur.fetchall()]

    def get_product(self, product_id: str) -> Product | None:
        with self.conn.cursor() as cur:
            cur.execute(_q(_schema.GET_PRODUCT), (product_id,))
            row = cur.fetchone()
        if row is None:
            return None
        return Product(
            product_id=row[0],
            store=row[1],
            title=row[2],
            seller_name=row[3],
            seller_type=SellerType(row[4]) if row[4] else SellerType.UNKNOWN,
            product_image=row[5],
            product_url=row[6],
            category=row[7],
            currency=row[8] or "SAR",
        )

    @staticmethod
    def _row_to_obs(row) -> PriceObservation:
        ts = row[0]
        if ts.tzinfo is None:
            ts = ts.replace(tzinfo=timezone.utc)
        return PriceObservation(
            timestamp=ts,
            current_price=float(row[1]),
            before_price=float(row[2]) if row[2] is not None else None,
            displayed_discount=float(row[3]) if row[3] is not None else None,
            seller=row[4],
        )
