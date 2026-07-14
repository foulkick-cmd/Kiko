"""Shared schema and SQL statements for all SQL backends.

Statements use ``{p}`` as a placeholder token which each backend rewrites to its
own paramstyle (``?`` for SQLite, ``%s`` for psycopg). Keeping the schema in one
place is what makes PostgreSQL a drop-in replacement for SQLite.
"""

from __future__ import annotations


def ddl(*, autoincrement_pk: str, timestamp_type: str) -> list[str]:
    """Return CREATE TABLE / INDEX statements for the given dialect."""
    return [
        f"""
        CREATE TABLE IF NOT EXISTS products (
            product_id   TEXT PRIMARY KEY,
            store        TEXT NOT NULL,
            title        TEXT NOT NULL,
            seller_name  TEXT,
            seller_type  TEXT,
            product_image TEXT,
            product_url  TEXT,
            category     TEXT,
            currency     TEXT,
            first_seen   {timestamp_type},
            last_seen    {timestamp_type}
        )
        """,
        f"""
        CREATE TABLE IF NOT EXISTS scrape_runs (
            run_id          {autoincrement_pk},
            store           TEXT NOT NULL,
            started_at      {timestamp_type},
            finished_at     {timestamp_type},
            products_seen   INTEGER DEFAULT 0,
            products_stored INTEGER DEFAULT 0,
            status          TEXT,
            note            TEXT
        )
        """,
        f"""
        CREATE TABLE IF NOT EXISTS price_history (
            id                 {autoincrement_pk},
            product_id         TEXT NOT NULL,
            run_id             INTEGER,
            seller             TEXT,
            current_price      REAL,
            before_price       REAL,
            displayed_discount REAL,
            timestamp          {timestamp_type} NOT NULL
        )
        """,
        "CREATE INDEX IF NOT EXISTS idx_history_product_ts "
        "ON price_history (product_id, timestamp)",
        "CREATE INDEX IF NOT EXISTS idx_history_ts ON price_history (timestamp)",
        "CREATE INDEX IF NOT EXISTS idx_history_seller ON price_history (seller)",
    ]


# --- statements (with {p} placeholders) ------------------------------------- #
UPSERT_PRODUCT = """
INSERT INTO products
    (product_id, store, title, seller_name, seller_type, product_image,
     product_url, category, currency, first_seen, last_seen)
VALUES ({p}, {p}, {p}, {p}, {p}, {p}, {p}, {p}, {p}, {p}, {p})
ON CONFLICT (product_id) DO UPDATE SET
    store       = EXCLUDED.store,
    title       = EXCLUDED.title,
    seller_name = EXCLUDED.seller_name,
    seller_type = EXCLUDED.seller_type,
    product_image = EXCLUDED.product_image,
    product_url = EXCLUDED.product_url,
    category    = EXCLUDED.category,
    currency    = EXCLUDED.currency,
    last_seen   = EXCLUDED.last_seen
"""

APPEND_PRICE = """
INSERT INTO price_history
    (product_id, run_id, seller, current_price, before_price,
     displayed_discount, timestamp)
VALUES ({p}, {p}, {p}, {p}, {p}, {p}, {p})
"""

LAST_PRICE = """
SELECT current_price FROM price_history
WHERE product_id = {p}
ORDER BY timestamp DESC
LIMIT 1
"""

PRICE_HISTORY = """
SELECT timestamp, current_price, before_price, displayed_discount, seller
FROM price_history
WHERE product_id = {p} AND timestamp >= {p}
ORDER BY timestamp ASC
"""

PRICE_HISTORY_ALL = """
SELECT timestamp, current_price, before_price, displayed_discount, seller
FROM price_history
WHERE product_id = {p}
ORDER BY timestamp ASC
"""

ALL_PRODUCT_IDS = "SELECT product_id FROM products"

GET_PRODUCT = """
SELECT product_id, store, title, seller_name, seller_type, product_image,
       product_url, category, currency
FROM products WHERE product_id = {p}
"""

START_RUN = """
INSERT INTO scrape_runs (store, started_at, status)
VALUES ({p}, {p}, 'running')
"""

FINISH_RUN = """
UPDATE scrape_runs
SET finished_at = {p}, products_seen = {p}, products_stored = {p},
    status = {p}, note = {p}
WHERE run_id = {p}
"""
