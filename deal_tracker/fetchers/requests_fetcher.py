"""Requests-based fetcher (fast path).

Uses ``requests`` with a shared session, exponential-backoff retries for
transient failures, robots.txt enforcement and polite randomised delays.

This backend returns *raw HTML*. Whether that HTML actually contains product
data (some Noon pages are JS-rendered) is determined downstream by the parser
and the :class:`~deal_tracker.stores.base.StoreAdapter`, which may then escalate
to :class:`~deal_tracker.fetchers.playwright_fetcher.PlaywrightFetcher`.
"""

from __future__ import annotations

import logging

import requests
from tenacity import (
    RetryError,
    retry,
    retry_if_exception_type,
    stop_after_attempt,
    wait_exponential,
)

from .base import Fetcher, FetchResult, FetchStatus
from .http_utils import RobotsCache, looks_blocked, polite_delay

logger = logging.getLogger(__name__)


class _Transient(RuntimeError):
    """Internal marker for retryable failures."""


class RequestsFetcher(Fetcher):
    """Fetch pages with ``requests`` + BeautifulSoup-ready HTML."""

    name = "requests"

    def __init__(
        self,
        *,
        user_agent: str,
        timeout: float = 20.0,
        max_retries: int = 4,
        min_delay: float = 2.0,
        max_delay: float = 3.0,
        respect_robots: bool = True,
        robots_default_allow: bool = True,
    ) -> None:
        self.timeout = timeout
        self.max_retries = max(1, max_retries)
        self.min_delay = min_delay
        self.max_delay = max_delay
        self.respect_robots = respect_robots
        self.robots_default_allow = robots_default_allow

        self.session = requests.Session()
        self.session.headers.update(
            {
                "User-Agent": user_agent,
                "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Accept-Language": "en-SA,en;q=0.9,ar;q=0.8",
                "Accept-Encoding": "gzip, deflate, br",
                "Connection": "keep-alive",
            }
        )
        self.robots = RobotsCache(user_agent, timeout=timeout)

    # ------------------------------------------------------------------ #
    def _get(self, url: str) -> requests.Response:
        """Perform a single GET, raising :class:`_Transient` on retryable errors."""
        try:
            resp = self.session.get(url, timeout=self.timeout, allow_redirects=True)
        except requests.RequestException as exc:
            raise _Transient(str(exc)) from exc
        # 5xx and 429 are worth retrying; 4xx (except 429) are not.
        if resp.status_code >= 500 or resp.status_code == 429:
            raise _Transient(f"HTTP {resp.status_code}")
        return resp

    def fetch(self, url: str) -> FetchResult:
        """Fetch ``url`` politely, honouring robots.txt and backing off on blocks."""
        if self.respect_robots and not self.robots.allowed(
            url, default=self.robots_default_allow
        ):
            logger.warning("robots.txt disallows %s — skipping.", url)
            return FetchResult(url=url, status=FetchStatus.DISALLOWED, fetcher=self.name)

        polite_delay(self.min_delay, self.max_delay)

        retrying = retry(
            reraise=True,
            stop=stop_after_attempt(self.max_retries),
            wait=wait_exponential(multiplier=2, min=2, max=32),
            retry=retry_if_exception_type(_Transient),
            before_sleep=lambda rs: logger.warning(
                "Transient fetch failure for %s (attempt %d): %s",
                url,
                rs.attempt_number,
                rs.outcome.exception() if rs.outcome else "?",
            ),
        )

        try:
            resp = retrying(self._get)(url)
        except (_Transient, RetryError) as exc:
            logger.error("Giving up on %s after retries: %s", url, exc)
            return FetchResult(
                url=url, status=FetchStatus.ERROR, fetcher=self.name, error=str(exc)
            )

        html = resp.text
        if looks_blocked(html, resp.status_code):
            logger.warning(
                "Detected block/challenge for %s (HTTP %s). Not evading — "
                "caller should reduce frequency.",
                url,
                resp.status_code,
            )
            return FetchResult(
                url=url,
                status=FetchStatus.BLOCKED,
                html=html,
                http_status=resp.status_code,
                fetcher=self.name,
                final_url=resp.url,
            )

        if resp.status_code == 404:
            return FetchResult(
                url=url,
                status=FetchStatus.NOT_FOUND,
                http_status=404,
                fetcher=self.name,
                final_url=resp.url,
            )

        if resp.status_code >= 400:
            return FetchResult(
                url=url,
                status=FetchStatus.ERROR,
                html=html,
                http_status=resp.status_code,
                fetcher=self.name,
                final_url=resp.url,
                error=f"HTTP {resp.status_code}",
            )

        return FetchResult(
            url=url,
            status=FetchStatus.OK,
            html=html,
            http_status=resp.status_code,
            fetcher=self.name,
            final_url=resp.url,
        )

    def close(self) -> None:
        self.session.close()
