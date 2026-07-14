"""Noon Saudi store adapter.

.. important::
   The category URLs and parser strategies here target Noon Saudi
   (``https://www.noon.com/saudi-en`` / ``noon.sa``). Noon is a JavaScript
   storefront whose markup and embedded-payload shapes change over time. Always
   validate against live pages using ``scripts/verify_fetcher.py`` before
   trusting the output — this adapter is built to be resilient across the three
   extraction strategies, but selectors are not guaranteed stable.
"""

from __future__ import annotations

from ..config import ScrapeConfig
from .base import StoreAdapter


class NoonAdapter(StoreAdapter):
    store_key = "noon_sa"
    display_name = "Noon Saudi"
    base_url = "https://www.noon.com"

    #: Sensible default deal/category pages if none configured. These are
    #: starting points only — the user should curate them in config.
    DEFAULT_CATEGORY_URLS = [
        "https://www.noon.com/saudi-en/deals/",
    ]

    def category_page_urls(self, config: ScrapeConfig) -> list[str]:
        return config.category_urls or list(self.DEFAULT_CATEGORY_URLS)

    def category_from_url(self, url: str) -> str:
        """Best-effort readable category from the URL path."""
        # e.g. .../saudi-en/electronics/mobiles/ -> "electronics/mobiles"
        try:
            after = url.split("saudi-en/", 1)[1]
        except IndexError:
            return url
        return after.strip("/").replace("/", " / ") or "deals"
