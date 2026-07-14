"""Shared parsing helpers.

Noon (like many modern storefronts) is a JavaScript single-page application.
The most robust way to extract data is therefore, in order of preference:

1. **Structured data** — ``<script type="application/ld+json">`` blocks
   (schema.org ``Product`` / ``ItemList``). Stable and semantic.
2. **Embedded app state** — Next.js style ``__NEXT_DATA__`` / inline ``window``
   JSON payloads present in the initial HTML even before hydration.
3. **DOM selectors** — a last resort, brittle against markup changes.

These helpers make all three strategies easy to attempt. The *exact* JSON key
paths and CSS selectors for Noon live in the concrete parsers and MUST be
validated against live pages with ``scripts/verify_fetcher.py`` — markup and
payload shapes change over time.
"""

from __future__ import annotations

import json
import logging
import re
from typing import Any, Iterator

from bs4 import BeautifulSoup

logger = logging.getLogger(__name__)

_PRICE_RE = re.compile(r"[-+]?\d[\d,]*\.?\d*")


def make_soup(html: str) -> BeautifulSoup:
    """Build a BeautifulSoup tree, preferring the fast ``lxml`` parser."""
    try:
        return BeautifulSoup(html, "lxml")
    except Exception:  # noqa: BLE001 - lxml missing at runtime
        return BeautifulSoup(html, "html.parser")


def parse_price(value: Any) -> float | None:
    """Parse a price from arbitrary input (``"SAR 1,299.00"`` -> ``1299.0``)."""
    if value is None:
        return None
    if isinstance(value, (int, float)):
        return float(value)
    match = _PRICE_RE.search(str(value).replace(",", ""))
    if not match:
        return None
    try:
        return float(match.group(0))
    except ValueError:
        return None


def parse_int(value: Any) -> int | None:
    """Parse an integer count (e.g. review count) from arbitrary input."""
    if value is None:
        return None
    if isinstance(value, bool):
        return None
    if isinstance(value, (int, float)):
        return int(value)
    match = re.search(r"\d[\d,]*", str(value))
    if not match:
        return None
    return int(match.group(0).replace(",", ""))


def iter_jsonld(soup: BeautifulSoup) -> Iterator[dict[str, Any]]:
    """Yield every JSON-LD object found in the document (flattening @graph)."""
    for tag in soup.find_all("script", attrs={"type": "application/ld+json"}):
        raw = tag.string or tag.get_text() or ""
        raw = raw.strip()
        if not raw:
            continue
        try:
            data = json.loads(raw)
        except json.JSONDecodeError:
            logger.debug("Skipping malformed JSON-LD block")
            continue
        for obj in _flatten_jsonld(data):
            yield obj


def _flatten_jsonld(data: Any) -> Iterator[dict[str, Any]]:
    if isinstance(data, list):
        for item in data:
            yield from _flatten_jsonld(item)
    elif isinstance(data, dict):
        if "@graph" in data and isinstance(data["@graph"], list):
            for item in data["@graph"]:
                yield from _flatten_jsonld(item)
        yield data


def find_next_data(soup: BeautifulSoup) -> dict[str, Any] | None:
    """Return the parsed ``__NEXT_DATA__`` payload if present."""
    tag = soup.find("script", id="__NEXT_DATA__")
    if tag is None:
        return None
    raw = tag.string or tag.get_text() or ""
    try:
        return json.loads(raw)
    except json.JSONDecodeError:
        logger.debug("Malformed __NEXT_DATA__ payload")
        return None


def deep_find_lists(obj: Any, keys: tuple[str, ...]) -> Iterator[list[Any]]:
    """Yield every list stored under any of ``keys`` anywhere in a nested dict.

    Useful for locating a catalogue array inside a large embedded app state
    without hard-coding a fragile key path.
    """
    if isinstance(obj, dict):
        for key, value in obj.items():
            if key in keys and isinstance(value, list):
                yield value
            yield from deep_find_lists(value, keys)
    elif isinstance(obj, list):
        for item in obj:
            yield from deep_find_lists(item, keys)


def first(*values: Any) -> Any:
    """Return the first non-None, non-empty value."""
    for v in values:
        if v not in (None, "", [], {}):
            return v
    return None
