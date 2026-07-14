"""Command-line entry point for Kiko.

Usage examples::

    python -m deal_tracker.main run                  # one full tracking run
    python -m deal_tracker.main run --store noon_sa
    python -m deal_tracker.main verify URL           # check fetch+parse of a URL
    python -m deal_tracker.main init-db              # create the database schema
    python -m deal_tracker.main report               # print top deals from CSV
"""

from __future__ import annotations

import argparse
import logging
import sys

from .config import load_config
from .logging_setup import setup_logging

logger = logging.getLogger("kiko")


def _cmd_run(args: argparse.Namespace) -> int:
    from .scheduler import run_once

    config = load_config(args.config)
    summary = run_once(config, store_key=args.store)
    print(
        f"seen={summary.products_seen} stored={summary.products_stored} "
        f"ranked={summary.deals_ranked} alerts={summary.alerts_sent} "
        f"errors={len(summary.errors)}"
    )
    if summary.csv_path:
        print(f"CSV: {summary.csv_path}")
    return 0 if not summary.errors else 2


def _cmd_init_db(args: argparse.Namespace) -> int:
    from .storage import get_storage

    config = load_config(args.config)
    storage = get_storage(config.storage)
    storage.initialize()
    storage.close()
    print(f"Initialised {config.storage.backend} storage.")
    return 0


def _cmd_verify(args: argparse.Namespace) -> int:
    """Fetch + parse a single URL and print what was extracted.

    This is the primary way to validate the scraper against LIVE pages.
    """
    from .fetchers import RequestsFetcher
    from .stores import get_adapter

    config = load_config(args.config)
    adapter = get_adapter(args.store)
    fetcher = RequestsFetcher(
        user_agent=config.scrape.user_agent,
        timeout=config.scrape.request_timeout,
        max_retries=config.scrape.max_retries,
        min_delay=config.scrape.min_delay_seconds,
        max_delay=config.scrape.max_delay_seconds,
        respect_robots=config.scrape.respect_robots,
    )
    pw_factory = None
    if config.scrape.allow_playwright:
        def pw_factory():  # type: ignore[misc]
            from .fetchers import PlaywrightFetcher

            return PlaywrightFetcher(user_agent=config.scrape.user_agent)

    with fetcher:
        if args.product:
            from .parsers import ProductRef

            ref = ProductRef(product_id="cli", product_url=args.url)
            product = adapter.fetch_product(
                ref, category=None, requests_fetcher=fetcher,
                playwright_factory=pw_factory,
                allow_playwright=config.scrape.allow_playwright,
            )
            if product is None:
                print("Could not extract a product from that URL.")
                return 1
            for k, v in product.to_row().items():
                print(f"  {k:20}: {v}")
            return 0
        refs, result = adapter.list_products(
            args.url, requests_fetcher=fetcher,
            playwright_factory=pw_factory,
            allow_playwright=config.scrape.allow_playwright,
        )
        print(f"fetcher={result.fetcher} status={result.status} "
              f"http={result.http_status} products={len(refs)}")
        for ref in refs[:20]:
            print(f"  {ref.product_id:16} {str(ref.current_price):>10}  "
                  f"{(ref.title or '')[:60]}")
    return 0


def _cmd_report(args: argparse.Namespace) -> int:
    import csv

    config = load_config(args.config)
    path = config.output.csv_path
    try:
        with open(path, newline="", encoding="utf-8") as fh:
            rows = list(csv.DictReader(fh))
    except FileNotFoundError:
        print(f"No CSV at {path}. Run a scrape first.")
        return 1
    rows.sort(key=lambda r: float(r.get("score") or 0), reverse=True)
    for r in rows[: args.top]:
        print(f"{r['score']:>6}  {r['confidence']:<6}  "
              f"{r['current_price']:>10} {r.get('title', '')[:60]}")
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="kiko", description="Noon Saudi deal tracker")
    parser.add_argument("--config", help="Path to YAML config", default=None)
    parser.add_argument("--log-level", default=None, help="DEBUG/INFO/WARNING/…")
    sub = parser.add_subparsers(dest="command", required=True)

    p_run = sub.add_parser("run", help="Execute one full tracking run")
    p_run.add_argument("--store", default="noon_sa")
    p_run.set_defaults(func=_cmd_run)

    p_init = sub.add_parser("init-db", help="Create database schema")
    p_init.set_defaults(func=_cmd_init_db)

    p_verify = sub.add_parser("verify", help="Fetch+parse a URL and print results")
    p_verify.add_argument("url")
    p_verify.add_argument("--store", default="noon_sa")
    p_verify.add_argument("--product", action="store_true",
                          help="Treat URL as a product page (default: category)")
    p_verify.set_defaults(func=_cmd_verify)

    p_report = sub.add_parser("report", help="Print top deals from the CSV")
    p_report.add_argument("--top", type=int, default=20)
    p_report.set_defaults(func=_cmd_report)

    return parser


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)

    config = load_config(args.config)
    setup_logging(
        level=args.log_level or config.output.log_level,
        log_dir=config.output.log_dir,
    )
    try:
        return args.func(args)
    except KeyboardInterrupt:
        logger.warning("Interrupted.")
        return 130


if __name__ == "__main__":
    sys.exit(main())
