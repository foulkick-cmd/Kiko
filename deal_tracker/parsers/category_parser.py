"""Parse Noon category / deal listing pages into product references.

A *category page* lists many products. We extract lightweight
:class:`ProductRef` objects (id, url, and whatever price/title is available in
the listing). The full detail is later fetched/parsed per product by
:class:`~deal_tracker.parsers.product_parser.ProductParser` when needed.

Extraction strategy (see :mod:`deal_tracker.parsers.base`):

1. JSON-LD ``ItemList``.
2. Embedded app-state catalogue arrays (``__NEXT_DATA__`` / inline JSON).
3. DOM anchors as a last resort.
"""

from __future__ import annotations

import logging
import re
from dataclasses import dataclass
from typing import Any
from urllib.parse import urljoin

from bs4 import BeautifulSoup

from .base import (
    deep_find_lists,
    find_next_data,
    first,
    iter_jsonld,
    make_soup,
    parse_price,
)

logger = logging.getLogger(__name__)

# Noon product URLs look like /saudi-en/<slug>/<SKU>/p/  — the SKU is the id.
_PRODUCT_URL_RE = re.compile(r"/([A-Z0-9]{8,})(?:/p)?/?(?:\?|$)")
# Keys under which Noon-like payloads tend to store product arrays.
_CATALOG_KEYS = ("hits", "products", "catalog", "productList", "items")


@dataclass(slots=True)
class ProductRef:
    """A minimal reference to a product discovered on a listing page."""

    product_id: str
    product_url: str | None = None
    title: str | None = None
    current_price: float | None = None
    before_price: float | None = None
    seller_name: str | None = None
    image: str | None = None


class CategoryParser:
    """Extract product references from a category/deal listing page."""

    def __init__(self, base_url: str = "https://www.noon.com") -> None:
        self.base_url = base_url.rstrip("/")

    # ------------------------------------------------------------------ #
    def parse(self, html: str, *, page_url: str | None = None) -> list[ProductRef]:
        """Return all product references found on the page (deduplicated)."""
        soup = make_soup(html)
        refs: dict[str, ProductRef] = {}

        for ref in self._from_jsonld(soup):
            refs.setdefault(ref.product_id, ref)
        for ref in self._from_app_state(soup):
            refs.setdefault(ref.product_id, ref)
        if not refs:
            for ref in self._from_dom(soup):
                refs.setdefault(ref.product_id, ref)

        logger.info("Category parser found %d product(s)", len(refs))
        return list(refs.values())

    # --- strategy 1: JSON-LD ItemList --------------------------------- #
    def _from_jsonld(self, soup: BeautifulSoup) -> list[ProductRef]:
        out: list[ProductRef] = []
        for obj in iter_jsonld(soup):
            if obj.get("@type") not in ("ItemList", "ItemPage"):
                continue
            for element in obj.get("itemListElement", []) or []:
                item = element.get("item", element) if isinstance(element, dict) else {}
                url = first(item.get("url"), element.get("url") if isinstance(element, dict) else None)
                pid = self._id_from_url(url) or item.get("sku") or item.get("productID")
                if not pid:
                    continue
                offers = item.get("offers") or {}
                if isinstance(offers, list):
                    offers = offers[0] if offers else {}
                out.append(
                    ProductRef(
                        product_id=str(pid),
                        product_url=self._abs(url),
                        title=item.get("name"),
                        current_price=parse_price(offers.get("price")),
                        image=self._first_image(item.get("image")),
                    )
                )
        return out

    # --- strategy 2: embedded app state ------------------------------- #
    def _from_app_state(self, soup: BeautifulSoup) -> list[ProductRef]:
        payload = find_next_data(soup)
        out: list[ProductRef] = []
        if payload is None:
            return out
        for array in deep_find_lists(payload, _CATALOG_KEYS):
            for item in array:
                if not isinstance(item, dict):
                    continue
                ref = self._ref_from_obj(item)
                if ref is not None:
                    out.append(ref)
        return out

    def _ref_from_obj(self, item: dict[str, Any]) -> ProductRef | None:
        pid = first(item.get("sku"), item.get("id"), item.get("productId"), item.get("product_id"))
        if not pid:
            return None
        price = first(item.get("sale_price"), item.get("price"), item.get("salePrice"))
        before = first(item.get("was_price"), item.get("wasPrice"), item.get("strikeThroughPrice"))
        seller = None
        if isinstance(item.get("offer_store"), dict):
            seller = item["offer_store"].get("name")
        seller = first(seller, item.get("seller"), item.get("sellerName"))
        url = first(item.get("url"), item.get("product_url"))
        return ProductRef(
            product_id=str(pid),
            product_url=self._abs(url),
            title=first(item.get("name"), item.get("title")),
            current_price=parse_price(price),
            before_price=parse_price(before),
            seller_name=seller,
            image=self._first_image(item.get("image") or item.get("image_key")),
        )

    # --- strategy 3: DOM ---------------------------------------------- #
    def _from_dom(self, soup: BeautifulSoup) -> list[ProductRef]:
        out: list[ProductRef] = []
        seen: set[str] = set()
        for anchor in soup.find_all("a", href=True):
            href = anchor["href"]
            pid = self._id_from_url(href)
            if not pid or pid in seen:
                continue
            seen.add(pid)
            title = anchor.get("title") or anchor.get_text(strip=True) or None
            out.append(
                ProductRef(
                    product_id=pid,
                    product_url=self._abs(href),
                    title=title or None,
                )
            )
        return out

    # ------------------------------------------------------------------ #
    def _id_from_url(self, url: str | None) -> str | None:
        if not url:
            return None
        match = _PRODUCT_URL_RE.search(url)
        return match.group(1) if match else None

    def _abs(self, url: str | None) -> str | None:
        if not url:
            return None
        if url.startswith("http"):
            return url
        return urljoin(self.base_url + "/", url.lstrip("/"))

    @staticmethod
    def _first_image(image: Any) -> str | None:
        if isinstance(image, list):
            return image[0] if image else None
        if isinstance(image, dict):
            return image.get("url")
        return image
