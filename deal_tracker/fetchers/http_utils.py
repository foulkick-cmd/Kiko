"""Shared HTTP helpers: robots.txt handling, polite delays, block detection.

These are intentionally dependency-light so both the Requests and Playwright
fetchers can reuse them.
"""

from __future__ import annotations

import logging
import random
import time
import urllib.robotparser
from urllib.parse import urljoin, urlparse

logger = logging.getLogger(__name__)

# Substrings that strongly suggest an anti-bot / challenge page rather than
# real content. We DO NOT attempt to solve these — we detect and back off.
_CHALLENGE_MARKERS = (
    "cf-browser-verification",
    "cf-challenge",
    "just a moment...",
    "attention required! | cloudflare",
    "/cdn-cgi/challenge-platform",
    "px-captcha",
    "please enable javascript and cookies",
    "access denied",
    "request unsuccessful. incapsula",
)


class RobotsCache:
    """Caches and evaluates ``robots.txt`` per host.

    On any error fetching robots.txt we *fail open* only for read parsing but
    log clearly; callers decide policy. The default policy (see
    :meth:`allowed`) is conservative: if robots cannot be read, we allow but
    warn, because many CDNs return non-200 for robots while still permitting
    normal crawling — however this is configurable by the caller.
    """

    def __init__(self, user_agent: str, *, timeout: float = 15.0) -> None:
        self.user_agent = user_agent
        self.timeout = timeout
        self._parsers: dict[str, urllib.robotparser.RobotFileParser | None] = {}

    def _parser_for(self, url: str) -> urllib.robotparser.RobotFileParser | None:
        parsed = urlparse(url)
        host = f"{parsed.scheme}://{parsed.netloc}"
        if host in self._parsers:
            return self._parsers[host]

        robots_url = urljoin(host, "/robots.txt")
        rp = urllib.robotparser.RobotFileParser()
        rp.set_url(robots_url)
        try:
            rp.read()
            logger.debug("Loaded robots.txt from %s", robots_url)
        except Exception as exc:  # noqa: BLE001 - network / parse errors
            logger.warning("Could not read %s (%s); treating as unknown.", robots_url, exc)
            rp = None  # type: ignore[assignment]
        self._parsers[host] = rp
        return rp

    def allowed(self, url: str, *, default: bool = True) -> bool:
        """Return whether ``url`` may be fetched by our user agent."""
        rp = self._parser_for(url)
        if rp is None:
            return default
        return rp.can_fetch(self.user_agent, url)

    def crawl_delay(self, url: str) -> float | None:
        rp = self._parser_for(url)
        if rp is None:
            return None
        try:
            delay = rp.crawl_delay(self.user_agent)
            return float(delay) if delay is not None else None
        except Exception:  # noqa: BLE001
            return None


def polite_delay(min_seconds: float, max_seconds: float) -> None:
    """Sleep a randomised amount between requests to avoid hammering a host."""
    low, high = sorted((max(0.0, min_seconds), max(0.0, max_seconds)))
    delay = random.uniform(low, high)
    logger.debug("Sleeping %.2fs before next request", delay)
    time.sleep(delay)


def looks_blocked(html: str, http_status: int | None) -> bool:
    """Heuristically detect an anti-bot / block page.

    We look at both the HTTP status (403/429) and known challenge markers in
    the body. This never attempts to bypass anything — it only classifies.
    """
    if http_status in (401, 403, 429, 503):
        return True
    if not html:
        return False
    lowered = html[:20000].lower()
    return any(marker in lowered for marker in _CHALLENGE_MARKERS)
