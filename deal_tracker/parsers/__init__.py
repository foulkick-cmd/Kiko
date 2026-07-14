"""HTML parsing layer: turn raw HTML into structured :class:`Product` objects."""

from __future__ import annotations

from .category_parser import CategoryParser, ProductRef
from .product_parser import ProductParser

__all__ = ["CategoryParser", "ProductParser", "ProductRef"]
