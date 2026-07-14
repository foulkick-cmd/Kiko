# Kiko — Noon Saudi Deal Tracker

A production-quality, modular Python deal tracker for **Noon Saudi**
(`https://www.noon.com/saudi-en` / `noon.sa`). It scrapes category/deal pages,
stores every price snapshot, distinguishes **real discounts from fake ones**
using each product's own price history, scores and ranks deals, and sends clean
Telegram alerts (with a 90-day price chart) when a genuine drop happens.

Built to be extended to other stores (Amazon, Jarir, Extra, Carrefour) through a
single `StoreAdapter` interface.

---

## ⚠️ Important: live verification status

Per the project's execution order, the scraper must be verified against **live**
Noon pages before the analytics are trusted. This repository was generated in a
sandbox whose **network policy blocks all Noon hosts** (`noon.com`, `noon.sa`,
`api.noon.com` all return a `403` egress-policy denial). Live Noon fetching could
therefore **not** be exercised here, and no attempt was made to bypass it.

What **has** been verified offline (all green):

* the fetcher's real HTTP behaviour — fetch, retry with exponential backoff,
  robots.txt handling, block detection, graceful failure — proven against a
  local HTTP server;
* both parsers extracting full product data (title, price, before-price, seller,
  seller type, rating, reviews, image, discount) from realistic Noon-shaped HTML;
* the entire pipeline end-to-end (store → analytics → ranking → CSV → chart →
  dry-run alert) via `tests/test_integration.py`.

**Before deploying, run the verifier from a network that can reach Noon** (see
[Verifying the scraper](#verifying-the-scraper)). The parsers use three resilient
strategies (JSON-LD → embedded app-state → DOM), but Noon's exact payload shapes
change over time and the selectors should be confirmed against a live page.

---

## Features

| Area | What it does |
|------|--------------|
| **Scraper** | `RequestsFetcher` first; auto-escalates to `PlaywrightFetcher` only when required fields are missing due to JS rendering. Randomised 2–3 s delays, robots.txt respected, exponential-backoff retries, block detection (never evades anti-bot). |
| **Storage** | SQLite by default; **PostgreSQL is a drop-in** (same schema/SQL). Tables: `products`, `price_history`, `scrape_runs`. Indexes on `(product_id, timestamp)`, `timestamp`, `seller`. Every scrape appends — history is never overwritten. |
| **Real-discount detection** | Ignores Noon's displayed "before" price. Computes 90-day min/avg/median, days-of-history, days-at-current-price, stability-before-drop, last change & %. Classifies `real` / `fake` / `history_insufficient`. |
| **Confidence** | 🟢/🟡/🔴 label from history length, observations, scrape consistency, stability pattern and sudden-drop confidence. |
| **Ranking** | `0.4·discount + 0.3·history_confidence + 0.2·rating + 0.1·seller`. Filter by min discount %, min rating, seller type, confidence. |
| **Change detection** | Alerts only when price drops below the last recorded price; skips unchanged products and duplicate alerts (persisted state). |
| **Charts** | 90-day price PNG (matplotlib, headless) attached to alerts. |
| **Alerts** | Telegram message with title, image, current/previous/lowest/avg price, displayed discount, confidence, seller, URL, and chart. |
| **Outputs** | CSV, SQLite DB, Telegram alerts, rotating logs. |
| **Scheduler** | Run `run_once` daily via the provided cron or systemd timer examples. |

---

## Architecture

```
deal_tracker/
├── fetchers/          # Fetcher abstraction
│   ├── base.py            Fetcher ABC + FetchResult/FetchStatus
│   ├── requests_fetcher.py
│   ├── playwright_fetcher.py
│   └── http_utils.py      robots.txt, polite delays, block detection
├── parsers/
│   ├── base.py            JSON-LD / __NEXT_DATA__ / DOM helpers
│   ├── category_parser.py
│   └── product_parser.py
├── stores/            # StoreAdapter interface (extensibility point)
│   ├── base.py            orchestrates fetch+parse, requests→playwright escalation
│   └── noon.py            NoonAdapter
├── storage/
│   ├── base.py            Storage ABC
│   ├── _schema.py         shared DDL + SQL (one place -> easy Postgres swap)
│   ├── sqlite_storage.py
│   └── postgres_storage.py
├── analytics/
│   ├── discount_detection.py
│   ├── confidence.py
│   └── ranking.py
├── charts/price_chart.py
├── alerts/
│   ├── change_detection.py
│   └── telegram.py
├── exporters/csv_exporter.py
├── scheduler/scheduler.py   run_once(): the daily pipeline
├── config.py            YAML + env config
├── models.py            shared dataclasses
├── logging_setup.py
└── main.py              CLI
scripts/                verify_fetcher.py, kiko.cron, kiko.service, kiko.timer
tests/                  pytest suite + synthetic fixtures
```

**Adding a new store** = implement one `StoreAdapter` subclass (its base URL,
category URLs, and — if the markup differs — its own parsers) and register it in
`deal_tracker/stores/__init__.py`. Fetching, storage, analytics, charts and
alerts are all store-agnostic and reused unchanged.

---

## Install

Requires **Python 3.12+**.

```bash
python -m venv .venv && source .venv/bin/activate
pip install -e .                 # core
pip install -e ".[playwright]"   # + JS rendering  (then: playwright install chromium)
pip install -e ".[postgres]"     # + PostgreSQL backend
pip install -e ".[dev]"          # + pytest / mypy / ruff
```

## Configure

```bash
cp config.example.yaml config.yaml   # edit category_urls, thresholds, filters
cp .env.example .env                 # TELEGRAM_BOT_TOKEN, TELEGRAM_CHAT_ID, secrets
```

Configuration precedence: **environment variables** > **`config.yaml`** >
built-in defaults. Every option is documented in `config.example.yaml`.

---

## Usage

```bash
# Create the database schema
kiko init-db

# Verify the scraper against a live page (do this first!)
python scripts/verify_fetcher.py "https://www.noon.com/saudi-en/deals/"
python scripts/verify_fetcher.py --product "https://www.noon.com/saudi-en/<slug>/<SKU>/p/"

# Run one full tracking run (scrape -> store -> analyse -> rank -> CSV -> alerts)
kiko run

# Print the top deals from the CSV
kiko report --top 20
```

(`kiko` is installed as a console script; equivalently `python -m deal_tracker.main …`
or `python main.py …`.)

### Verifying the scraper

`scripts/verify_fetcher.py` is the ground-truth check. It fetches a URL with
Requests first (showing whether the page is JS-rendered or blocked), escalates to
Playwright if needed, and prints exactly which fields were extracted:

```bash
# Category / deal listing
python scripts/verify_fetcher.py "https://www.noon.com/saudi-en/deals/"

# Single product page, saving the raw HTML for inspection
python scripts/verify_fetcher.py --product --save-html /tmp/pdp.html \
    "https://www.noon.com/saudi-en/<slug>/<SKU>/p/"
```

If it reports `BLOCKED`, the host is rate-limiting/anti-bot — **reduce frequency
and retry later**, don't try to evade it. If products parse with `0` prices, the
embedded-payload/selector shapes have changed; update the strategies in
`deal_tracker/parsers/` (the saved HTML shows you the new shape).

---

## Real vs fake discount — the core logic

Noon's strikethrough "before" price is **not trusted**. Instead, for each product
over the trailing 90 days:

* `min / avg / median`, `days_of_history`, `days_at_current_price`,
  `stability_before_drop`, `last_price_change`, `price_change_percent`.
* **`is_sudden_drop`** — price stayed within ±5 % for ≥ 14–30 days, then fell
  ≥ 15 % within 1–3 days.

Classification:

* **`real_discount`** — `current ≤ 90d-min × 1.02` **and** `is_sudden_drop`.
* **`fake_discount`** — a "discounted" price that has been static for weeks
  (inflated before-price).
* **`history_insufficient`** — fewer than 30 days of history.

All thresholds live in `analytics:` in the config.

---

## Scheduling (daily)

**cron** (`scripts/kiko.cron`):

```cron
15 7 * * * cd /opt/kiko && /opt/kiko/.venv/bin/python -m deal_tracker.main run >> /opt/kiko/logs/cron.log 2>&1
```

**systemd** (`scripts/kiko.service` + `scripts/kiko.timer`):

```bash
sudo cp scripts/kiko.service scripts/kiko.timer /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now kiko.timer
systemctl list-timers kiko.timer
```

Each run appends new history; previous prices are never overwritten.

---

## Switching to PostgreSQL

```yaml
storage:
  backend: postgres
  postgres_dsn: "postgresql://user:pass@localhost:5432/kiko"
```

or set `KIKO_STORAGE_BACKEND=postgres` and `KIKO_POSTGRES_DSN=…`. The schema and
SQL are shared with SQLite (`deal_tracker/storage/_schema.py`); only the driver
and paramstyle differ.

---

## Tests

```bash
pytest            # parsers, discount detection, confidence, ranking, storage,
                  # change detection, outputs, and an end-to-end integration run
```

The suite runs fully offline using synthetic Noon-shaped fixtures and stubbed
fetching, so it is deterministic and CI-friendly.

---

## Responsible scraping

Kiko is deliberately polite: it obeys `robots.txt`, spaces requests by a
randomised 2–3 s, backs off exponentially on transient errors, and **detects but
never bypasses** anti-bot/Cloudflare challenges — on a block it logs and reduces
frequency. It does not assume any private mobile API exists. Please review Noon's
Terms of Service and use responsibly.

## License

MIT.
