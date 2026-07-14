"""The :class:`StoreAdapter` abstraction.

A store adapter orchestrates fetch + parse for one storefront. It also owns the
key policy from the spec: *try Requests first, detect whether product data is
present, and escalate to Playwright only when required fields are missing due to
JS rendering.*
"""

from __future__ import annotations

import abc
import logging
from typing import Iterable

from ..config import ScrapeConfig
from ..fetchers import Fetcher, FetchResult, FetchStatus, RequestsFetcher
from ..models import Product
from ..parsers import CategoryParser, ProductParser, ProductRef

logger = logging.getLogger(__name__)


class StoreAdapter(abc.ABC):
    """Base class for all storefront adapters."""

    #: Stable key used in storage/config (e.g. ``"noon_sa"``).
    store_key: str = "base"
    #: Human-facing name.
    display_name: str = "Base Store"
    #: Absolute base URL used to resolve relative links.
    base_url: str = ""

    def __init__(self) -> None:
        self.category_parser = CategoryParser(self.base_url)
        self.product_parser = ProductParser()

    # ------------------------------------------------------------------ #
    # Store-specific hooks
    # ------------------------------------------------------------------ #
    @abc.abstractmethod
    def category_page_urls(self, config: ScrapeConfig) -> list[str]:
        """Return the listing/deal URLs to crawl for this store."""

    def category_from_url(self, url: str) -> str:
        """Derive a human-readable category label from a listing URL."""
        return url

    # ------------------------------------------------------------------ #
    # Orchestration (store-agnostic, reusable)
    # ------------------------------------------------------------------ #
    def list_products(
        self,
        url: str,
        *,
        requests_fetcher: RequestsFetcher,
        playwright_factory=None,
        allow_playwright: bool = True,
    ) -> tuple[list[ProductRef], FetchResult]:
        """Fetch and parse a listing page, escalating to Playwright if needed."""
        result = requests_fetcher.fetch(url)
        refs: list[ProductRef] = []
        if result.ok:
            refs = self.category_parser.parse(result.html, page_url=url)

        needs_js = (not refs) or all(r.current_price is None for r in refs)
        if needs_js and allow_playwright and playwright_factory is not None:
            if result.status is FetchStatus.BLOCKED:
                logger.warning("Listing %s blocked; not escalating to evade.", url)
            else:
                logger.info("Listing %s incomplete via Requests — escalating to Playwright.", url)
                with playwright_factory() as pw:
                    pw_result = pw.fetch(url)
                if pw_result.ok:
                    refs = self.category_parser.parse(pw_result.html, page_url=url)
                    result = pw_result
        return refs, result

    def fetch_product(
        self,
        ref: ProductRef,
        *,
        category: str | None,
        requests_fetcher: RequestsFetcher,
        playwright_factory=None,
        allow_playwright: bool = True,
    ) -> Product | None:
        """Fetch and parse a single product, escalating to Playwright if needed."""
        if not ref.product_url:
            logger.debug("Product %s has no URL; using listing data only.", ref.product_id)
            return self._product_from_ref(ref, category)

        result = requests_fetcher.fetch(ref.product_url)
        product: Product | None = None
        if result.ok:
            product = self.product_parser.parse(
                result.html,
                product_url=ref.product_url,
                category=category,
                fallback_id=ref.product_id,
            )

        if self._needs_js(product) and allow_playwright and playwright_factory is not None:
            if result.status is FetchStatus.BLOCKED:
                logger.warning("Product %s blocked; not escalating to evade.", ref.product_url)
            else:
                logger.info("Product %s incomplete via Requests — escalating to Playwright.", ref.product_id)
                with playwright_factory() as pw:
                    pw_result = pw.fetch(ref.product_url)
                if pw_result.ok:
                    product = self.product_parser.parse(
                        pw_result.html,
                        product_url=ref.product_url,
                        category=category,
                        fallback_id=ref.product_id,
                    )

        if product is None:
            product = self._product_from_ref(ref, category)
        else:
            self._merge_ref(product, ref)
        return product

    # ------------------------------------------------------------------ #
    @staticmethod
    def _needs_js(product: Product | None) -> bool:
        """True when Requests-only parsing failed to yield the required fields."""
        return product is None or not product.is_complete()

    def _product_from_ref(self, ref: ProductRef, category: str | None) -> Product | None:
        if not ref.title or ref.current_price is None:
            return None
        from ..models import Product as _P  # local import to avoid cycle at type-check

        p = _P(
            product_id=ref.product_id,
            title=ref.title,
            store=self.store_key,
            seller_name=ref.seller_name,
            current_price=ref.current_price,
            before_price=ref.before_price,
            product_image=ref.image,
            product_url=ref.product_url,
            category=category,
        )
        return p

    @staticmethod
    def _merge_ref(product: Product, ref: ProductRef) -> None:
        """Fill any product gaps with listing-page data."""
        if product.product_image is None:
            product.product_image = ref.image
        if product.before_price is None:
            product.before_price = ref.before_price
        if product.seller_name is None:
            product.seller_name = ref.seller_name

    def iter_all_products(
        self,
        config: ScrapeConfig,
        *,
        requests_fetcher: RequestsFetcher,
        playwright_factory=None,
    ) -> Iterable[Product]:
        """Yield every product across all configured category pages."""
        for url in self.category_page_urls(config):
            category = self.category_from_url(url)
            refs, _ = self.list_products(
                url,
                requests_fetcher=requests_fetcher,
                playwright_factory=playwright_factory,
                allow_playwright=config.allow_playwright,
            )
            for ref in refs[: config.max_products_per_category]:
                product = self.fetch_product(
                    ref,
                    category=category,
                    requests_fetcher=requests_fetcher,
                    playwright_factory=playwright_factory,
                    allow_playwright=config.allow_playwright,
                )
                if product is not None and product.is_complete():
                    yield product
                else:
                    logger.debug("Skipping incomplete product %s", ref.product_id)
