"""Parse a Noon product detail page into a full :class:`Product`.

Same three-strategy approach as the category parser. The parser merges data
from all available sources, preferring structured data over the DOM.
"""

from __future__ import annotations

import logging
from typing import Any

from bs4 import BeautifulSoup

from ..models import Product, SellerType, utcnow
from .base import (
    deep_find_lists,
    find_next_data,
    first,
    iter_jsonld,
    make_soup,
    parse_int,
    parse_price,
)

logger = logging.getLogger(__name__)

_PRODUCT_KEYS = ("product", "productDetails", "sku", "detail")


class ProductParser:
    """Turn a product-detail HTML page into a :class:`Product`."""

    def parse(
        self,
        html: str,
        *,
        product_url: str | None = None,
        category: str | None = None,
        fallback_id: str | None = None,
    ) -> Product | None:
        """Parse ``html`` into a :class:`Product`, or ``None`` if not derivable."""
        soup = make_soup(html)

        data = self._from_jsonld(soup)
        app = self._from_app_state(soup)
        # Merge: app-state values fill gaps left by JSON-LD.
        merged = {**app, **{k: v for k, v in data.items() if v is not None}}

        if not merged.get("product_id") and fallback_id:
            merged["product_id"] = fallback_id
        if not merged.get("title"):
            dom_title = self._dom_title(soup)
            if dom_title:
                merged["title"] = dom_title
        if merged.get("current_price") is None:
            merged["current_price"] = self._dom_price(soup)

        if not merged.get("product_id") or not merged.get("title"):
            logger.warning("Could not extract product id/title from page %s", product_url)
            return None

        seller_name = merged.get("seller_name")
        product = Product(
            product_id=str(merged["product_id"]),
            title=str(merged["title"]),
            seller_name=seller_name,
            seller_type=self._classify_seller(seller_name, merged.get("seller_type")),
            current_price=parse_price(merged.get("current_price")),
            before_price=parse_price(merged.get("before_price")),
            displayed_discount_percent=self._discount(
                parse_price(merged.get("current_price")),
                parse_price(merged.get("before_price")),
                merged.get("displayed_discount_percent"),
            ),
            rating=self._as_float(merged.get("rating")),
            review_count=parse_int(merged.get("review_count")),
            product_image=merged.get("product_image"),
            product_url=product_url or merged.get("product_url"),
            category=category or merged.get("category"),
            currency=merged.get("currency") or "SAR",
            timestamp=utcnow(),
        )
        return product

    # --- strategy 1: JSON-LD Product ---------------------------------- #
    def _from_jsonld(self, soup: BeautifulSoup) -> dict[str, Any]:
        for obj in iter_jsonld(soup):
            if obj.get("@type") != "Product":
                continue
            offers = obj.get("offers") or {}
            if isinstance(offers, list):
                offers = offers[0] if offers else {}
            rating = obj.get("aggregateRating") or {}
            seller = offers.get("seller") or {}
            seller_name = seller.get("name") if isinstance(seller, dict) else seller
            return {
                "product_id": first(obj.get("sku"), obj.get("productID"), obj.get("mpn")),
                "title": obj.get("name"),
                "current_price": offers.get("price"),
                "before_price": first(
                    offers.get("highPrice"), offers.get("listPrice")
                ),
                "currency": offers.get("priceCurrency"),
                "rating": rating.get("ratingValue"),
                "review_count": first(rating.get("reviewCount"), rating.get("ratingCount")),
                "product_image": self._first_image(obj.get("image")),
                "seller_name": seller_name,
                "category": obj.get("category"),
            }
        return {}

    # --- strategy 2: embedded app state ------------------------------- #
    def _from_app_state(self, soup: BeautifulSoup) -> dict[str, Any]:
        payload = find_next_data(soup)
        if payload is None:
            return {}
        # Look for a dict that has the hallmarks of a product record.
        best: dict[str, Any] = {}
        for array in deep_find_lists(payload, ("variants", "products", "hits")):
            for item in array:
                if isinstance(item, dict) and (item.get("sku") or item.get("product_id")):
                    best = self._map_app_obj(item)
                    break
            if best:
                break
        if best:
            return best
        # Otherwise scan nested dicts under likely product keys.
        found = self._search_product_dict(payload)
        return self._map_app_obj(found) if found else {}

    def _search_product_dict(self, obj: Any) -> dict[str, Any] | None:
        if isinstance(obj, dict):
            if (obj.get("sku") or obj.get("product_id")) and (obj.get("name") or obj.get("title")):
                return obj
            for key, value in obj.items():
                if key in _PRODUCT_KEYS or isinstance(value, (dict, list)):
                    result = self._search_product_dict(value)
                    if result is not None:
                        return result
        elif isinstance(obj, list):
            for item in obj:
                result = self._search_product_dict(item)
                if result is not None:
                    return result
        return None

    def _map_app_obj(self, item: dict[str, Any]) -> dict[str, Any]:
        store = item.get("offer_store") if isinstance(item.get("offer_store"), dict) else {}
        seller_name = first(store.get("name"), item.get("seller"), item.get("sellerName"))
        seller_type = None
        if isinstance(item.get("is_fbn"), bool):
            # "Fulfilled by Noon" heuristic.
            seller_type = "Noon" if item["is_fbn"] else "Marketplace"
        return {
            "product_id": first(item.get("sku"), item.get("product_id"), item.get("id")),
            "title": first(item.get("name"), item.get("title")),
            "current_price": first(item.get("sale_price"), item.get("price")),
            "before_price": first(item.get("was_price"), item.get("wasPrice")),
            "displayed_discount_percent": first(item.get("discount"), item.get("discount_percent")),
            "currency": item.get("currency"),
            "rating": first(item.get("product_rating", {}).get("value")
                            if isinstance(item.get("product_rating"), dict) else None,
                            item.get("rating")),
            "review_count": first(item.get("product_rating", {}).get("count")
                                  if isinstance(item.get("product_rating"), dict) else None,
                                  item.get("review_count")),
            "product_image": self._first_image(item.get("image") or item.get("image_key")),
            "seller_name": seller_name,
            "seller_type": seller_type,
            "category": item.get("category"),
        }

    # --- DOM fallbacks ------------------------------------------------- #
    @staticmethod
    def _dom_title(soup: BeautifulSoup) -> str | None:
        for sel in ("h1", '[data-qa="pdp-name"]', "meta[property='og:title']"):
            tag = soup.select_one(sel)
            if tag is None:
                continue
            text = tag.get("content") if tag.name == "meta" else tag.get_text(strip=True)
            if text:
                return text
        return None

    @staticmethod
    def _dom_price(soup: BeautifulSoup) -> float | None:
        for sel in ('[data-qa="div-price-now"]', ".priceNow", "[class*=priceNow]"):
            tag = soup.select_one(sel)
            if tag is not None:
                price = parse_price(tag.get_text(strip=True))
                if price is not None:
                    return price
        return None

    # --- helpers ------------------------------------------------------- #
    @staticmethod
    def _classify_seller(seller_name: str | None, explicit: str | None) -> SellerType:
        if explicit:
            return SellerType.NOON if explicit.lower() == "noon" else SellerType.MARKETPLACE
        if not seller_name:
            return SellerType.UNKNOWN
        lowered = seller_name.strip().lower()
        if lowered in {"noon", "noon.com", "fulfilled by noon"}:
            return SellerType.NOON
        return SellerType.MARKETPLACE

    @staticmethod
    def _discount(current: float | None, before: float | None, displayed: Any) -> float | None:
        d = parse_price(displayed)
        if d is not None:
            return d
        if current is not None and before and before > 0:
            return round((1 - current / before) * 100, 2)
        return None

    @staticmethod
    def _as_float(value: Any) -> float | None:
        return parse_price(value)

    @staticmethod
    def _first_image(image: Any) -> str | None:
        if isinstance(image, list):
            return image[0] if image else None
        if isinstance(image, dict):
            return image.get("url")
        return image
