"""Orchestrate a single end-to-end tracking run.

Pipeline for one run:

1. Scrape products from the store's configured category/deal pages.
2. Persist each snapshot (upsert product + append price history).
3. For every product, analyse the last 90 days (discount / confidence / score).
4. Rank + filter, export to CSV.
5. Emit alerts for genuine price drops (with a 90-day chart).

Scheduling itself (running daily) is delegated to the OS — see the cron and
systemd examples in ``scripts/``. This function is the single unit a scheduler
invokes.
"""

from __future__ import annotations

import logging
from dataclasses import dataclass, field

from ..analytics import (
    analyze_history,
    apply_filters,
    rank_deals,
    score_confidence,
    score_deal,
)
from ..analytics.ranking import DealResult
from ..alerts.change_detection import AlertState, should_alert
from ..alerts.telegram import TelegramAlerter
from ..charts import render_price_chart
from ..config import Config
from ..exporters import write_csv
from ..fetchers import RequestsFetcher
from ..models import Product
from ..storage import Storage, get_storage
from ..stores import get_adapter
from ..stores.base import StoreAdapter

logger = logging.getLogger(__name__)


@dataclass(slots=True)
class RunSummary:
    store: str
    products_seen: int = 0
    products_stored: int = 0
    deals_ranked: int = 0
    alerts_sent: int = 0
    csv_path: str | None = None
    errors: list[str] = field(default_factory=list)


def _playwright_factory(config: Config):
    """Return a zero-arg callable that builds a PlaywrightFetcher, or None.

    Degrades gracefully: if Playwright is not installed we log once and return
    ``None`` so a run against JS-rendered pages does not crash — it simply
    cannot escalate.
    """
    if not config.scrape.allow_playwright:
        return None

    from ..fetchers import playwright_available

    if not playwright_available():
        logger.warning(
            "allow_playwright is set but Playwright is not installed; "
            "JS-render escalation is disabled. Install with "
            "`pip install playwright && playwright install chromium`."
        )
        return None

    def factory():
        from ..fetchers import PlaywrightFetcher

        return PlaywrightFetcher(
            user_agent=config.scrape.user_agent,
            timeout=config.scrape.request_timeout + 15,
            min_delay=config.scrape.min_delay_seconds,
            max_delay=config.scrape.max_delay_seconds,
            respect_robots=config.scrape.respect_robots,
        )

    return factory


def run_once(config: Config, *, store_key: str = "noon_sa") -> RunSummary:
    """Execute one full tracking run for a single store."""
    adapter: StoreAdapter = get_adapter(store_key)
    summary = RunSummary(store=store_key)

    requests_fetcher = RequestsFetcher(
        user_agent=config.scrape.user_agent,
        timeout=config.scrape.request_timeout,
        max_retries=config.scrape.max_retries,
        min_delay=config.scrape.min_delay_seconds,
        max_delay=config.scrape.max_delay_seconds,
        respect_robots=config.scrape.respect_robots,
    )
    pw_factory = _playwright_factory(config)

    storage = get_storage(config.storage)
    storage.initialize()
    run = storage.start_run(store_key)
    alert_state = AlertState(config.alerts.state_path)
    alerter = TelegramAlerter(
        config.alerts.telegram_bot_token, config.alerts.telegram_chat_id
    )

    results: list[DealResult] = []
    try:
        with requests_fetcher:
            products = adapter.iter_all_products(
                config.scrape,
                requests_fetcher=requests_fetcher,
                playwright_factory=pw_factory,
            )
            for product in products:
                summary.products_seen += 1
                # 1) capture the previous price BEFORE appending this snapshot.
                last_price = storage.last_price(product.product_id)
                # 2) persist snapshot (never overwrites history).
                try:
                    storage.record(product, run_id=run.run_id)
                    summary.products_stored += 1
                except Exception as exc:  # noqa: BLE001
                    logger.exception("Failed to store %s", product.product_id)
                    summary.errors.append(f"store {product.product_id}: {exc}")
                    continue

                result = _analyze(product, storage, config)
                results.append(result)

                # 3) alerting on genuine drops (alerter dry-runs when disabled).
                _maybe_alert(
                    product, result, last_price, storage, config,
                    alerter, alert_state, summary,
                )

        run.products_seen = summary.products_seen
        run.products_stored = summary.products_stored

        # 4) rank, filter, export.
        filtered = apply_filters(results, config.ranking)
        ranked = rank_deals(filtered)
        summary.deals_ranked = len(ranked)
        summary.csv_path = write_csv(ranked, config.output.csv_path, append=True)

        run.status = "success" if not summary.errors else "partial"
    except Exception as exc:  # noqa: BLE001
        logger.exception("Run failed")
        run.status = "failed"
        run.note = str(exc)[:500]
        summary.errors.append(str(exc))
    finally:
        alert_state.save()
        storage.finish_run(run)
        storage.close()

    logger.info(
        "Run complete: seen=%d stored=%d ranked=%d alerts=%d errors=%d",
        summary.products_seen, summary.products_stored,
        summary.deals_ranked, summary.alerts_sent, len(summary.errors),
    )
    return summary


def _analyze(product: Product, storage: Storage, config: Config) -> DealResult:
    """Run analytics for a single product using its stored history."""
    history = storage.price_history(product.product_id)
    stats, discount_class = analyze_history(
        product.current_price, history, config=config.analytics
    )
    conf_score, confidence = score_confidence(
        stats, product.seller_type, config=config.analytics
    )
    analysis = score_deal(
        product, stats, discount_class, confidence, conf_score,
        config=config.ranking,
    )
    return DealResult(product=product, analysis=analysis)


def _maybe_alert(
    product: Product,
    result: DealResult,
    last_price: float | None,
    storage: Storage,
    config: Config,
    alerter: TelegramAlerter,
    alert_state: AlertState,
    summary: RunSummary,
) -> None:
    """Alert on a genuine price drop, honouring change-detection rules."""
    decision = should_alert(
        product.product_id,
        product.current_price,
        last_price,
        state=alert_state,
        min_drop_percent=config.alerts.min_drop_percent,
    )
    if not decision.should_send:
        logger.debug("No alert for %s: %s", product.product_id, decision.reason)
        return

    allow = config.alerts.confidence_allowlist
    if allow and result.analysis.confidence.value not in allow:
        logger.debug(
            "Skipping alert for %s: confidence %s not in allowlist",
            product.product_id, result.analysis.confidence.value,
        )
        return

    chart_path = None
    if config.alerts.attach_chart:
        history = storage.price_history(product.product_id)
        chart_path = render_price_chart(
            product.product_id, product.title, history,
            out_dir=config.output.charts_dir,
            window_days=config.analytics.window_days,
            stats=result.analysis.stats,
            currency=product.currency,
        )

    if alerter.send_deal(product, result.analysis, chart_path=chart_path):
        summary.alerts_sent += 1
        if product.current_price is not None:
            alert_state.record(product.product_id, product.current_price)
