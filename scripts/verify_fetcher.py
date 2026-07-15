#!/usr/bin/env python3
"""Standalone fetcher/parser verification against LIVE Noon pages.

This is the tool to run FIRST (per the project's execution order) in an
environment that has network access to Noon. It proves the scraper actually
retrieves real product data (title, price, seller) before any downstream
analytics are trusted.

    python scripts/verify_fetcher.py CATEGORY_URL
    python scripts/verify_fetcher.py --product PRODUCT_URL
    python scripts/verify_fetcher.py --save-html out.html CATEGORY_URL

It reports which fetcher backend succeeded (requests vs playwright), whether the
page was blocked, and prints the fields extracted for the first products.

NOTE: In the sandbox where this repository was generated, outbound access to
noon.com / noon.sa is blocked by network policy, so live verification must be
run wherever you actually deploy. See README "Verifying the scraper".
"""

from __future__ import annotations

import argparse
import logging
import sys
from pathlib import Path

# Allow running as a script without installing the package.
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from deal_tracker.config import load_config  # noqa: E402
from deal_tracker.fetchers import FetchStatus, RequestsFetcher  # noqa: E402
from deal_tracker.logging_setup import setup_logging  # noqa: E402
from deal_tracker.parsers import ProductRef  # noqa: E402
from deal_tracker.stores import get_adapter  # noqa: E402


def _pw_factory(config, log):
    """Build a Playwright factory, or None (with a clear note) if unavailable."""
    from deal_tracker.fetchers import playwright_available

    if not playwright_available():
        log.warning("Playwright not installed — cannot escalate to JS rendering. "
                    "Install with `pip install playwright && playwright install chromium`.")
        return None

    def factory():
        from deal_tracker.fetchers import PlaywrightFetcher

        return PlaywrightFetcher(user_agent=config.scrape.user_agent)

    return factory


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("url", help="Category/deal URL, or product URL with --product")
    parser.add_argument("--product", action="store_true")
    parser.add_argument("--store", default="noon_sa")
    parser.add_argument("--no-playwright", action="store_true")
    parser.add_argument("--save-html", metavar="PATH", default=None)
    parser.add_argument("--config", default=None)
    args = parser.parse_args()

    setup_logging(level="INFO", log_dir=None)
    log = logging.getLogger("verify")
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
    pw_factory = None if args.no_playwright else _pw_factory(config, log)

    with fetcher:
        # First show the raw requests-only result so JS-rendering is visible.
        raw = fetcher.fetch(args.url)
        log.info("Requests-only: status=%s http=%s bytes=%d",
                 raw.status, raw.http_status, len(raw.html))
        if args.save_html and raw.html:
            Path(args.save_html).write_text(raw.html, encoding="utf-8")
            log.info("Saved raw HTML to %s", args.save_html)

        if raw.status is FetchStatus.BLOCKED:
            log.error("Page is BLOCKED by anti-bot. Not evading — reduce frequency "
                      "and retry later, or run from an allowed network.")
            return 3
        if raw.status is FetchStatus.DISALLOWED:
            log.error("robots.txt disallows this URL for our user agent.")
            return 4
        if raw.status is FetchStatus.ERROR and pw_factory is None:
            log.error("Requests fetch failed (network/proxy/connection error: %s) and "
                      "Playwright escalation is unavailable — cannot verify. Run from a "
                      "network that can reach the host, or install Playwright.",
                      raw.error)
            return 5

        if args.product:
            ref = ProductRef(product_id="verify", product_url=args.url)
            product = adapter.fetch_product(
                ref, category=None, requests_fetcher=fetcher,
                playwright_factory=pw_factory,
                allow_playwright=not args.no_playwright,
            )
            if product is None:
                log.error("FAILED to extract a product. Selectors likely need "
                          "updating — inspect the saved HTML.")
                return 1
            print("\n=== Extracted product ===")
            for k, v in product.to_row().items():
                print(f"  {k:22}: {v}")
            ok = product.is_complete()
            print(f"\nComplete (id+title+price present): {ok}")
            return 0 if ok else 1

        refs, result = adapter.list_products(
            args.url, requests_fetcher=fetcher,
            playwright_factory=pw_factory,
            allow_playwright=not args.no_playwright,
        )
        print(f"\n=== Listing via {result.fetcher} (status={result.status}) ===")
        print(f"products found: {len(refs)}")
        for ref in refs[:15]:
            print(f"  {ref.product_id:16} {str(ref.current_price):>10}  "
                  f"{(ref.title or '')[:55]}")
        if not refs:
            log.error("No products parsed. Inspect saved HTML and update selectors.")
            return 1
        with_price = sum(1 for r in refs if r.current_price is not None)
        print(f"\nwith price: {with_price}/{len(refs)}")
        return 0 if with_price else 1


if __name__ == "__main__":
    sys.exit(main())
