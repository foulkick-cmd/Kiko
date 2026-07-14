"""Playwright-based fetcher (JS-rendered path).

This is **not** merely a fallback. The store adapter tries
:class:`RequestsFetcher` first, inspects whether the required product fields are
present, and escalates to this fetcher only when data is missing because the
page needs JavaScript rendering.

Playwright is an optional dependency; importing this module without it raises a
clear, actionable error.
"""

from __future__ import annotations

import logging

from .base import Fetcher, FetchResult, FetchStatus
from .http_utils import RobotsCache, looks_blocked, polite_delay

logger = logging.getLogger(__name__)

try:  # pragma: no cover - exercised only when playwright is installed
    from playwright.sync_api import TimeoutError as PWTimeoutError
    from playwright.sync_api import sync_playwright

    _PLAYWRIGHT_AVAILABLE = True
except ImportError:  # pragma: no cover
    _PLAYWRIGHT_AVAILABLE = False


class PlaywrightFetcher(Fetcher):
    """Render pages in a headless Chromium browser and return the DOM HTML."""

    name = "playwright"

    def __init__(
        self,
        *,
        user_agent: str,
        timeout: float = 30.0,
        min_delay: float = 2.0,
        max_delay: float = 3.0,
        respect_robots: bool = True,
        robots_default_allow: bool = True,
        headless: bool = True,
        wait_until: str = "networkidle",
        executable_path: str | None = None,
    ) -> None:
        if not _PLAYWRIGHT_AVAILABLE:
            raise RuntimeError(
                "Playwright is not installed. Install with "
                "`pip install playwright && playwright install chromium`."
            )
        self.user_agent = user_agent
        self.timeout_ms = int(timeout * 1000)
        self.min_delay = min_delay
        self.max_delay = max_delay
        self.respect_robots = respect_robots
        self.robots_default_allow = robots_default_allow
        self.headless = headless
        self.wait_until = wait_until
        self.executable_path = executable_path

        self.robots = RobotsCache(user_agent, timeout=timeout)
        self._pw = None
        self._browser = None

    # ------------------------------------------------------------------ #
    def _ensure_browser(self) -> None:
        if self._browser is not None:
            return
        self._pw = sync_playwright().start()
        launch_kwargs: dict = {"headless": self.headless}
        if self.executable_path:
            launch_kwargs["executable_path"] = self.executable_path
        self._browser = self._pw.chromium.launch(**launch_kwargs)
        logger.debug("Launched headless Chromium via Playwright")

    def fetch(self, url: str) -> FetchResult:
        if self.respect_robots and not self.robots.allowed(
            url, default=self.robots_default_allow
        ):
            logger.warning("robots.txt disallows %s — skipping.", url)
            return FetchResult(url=url, status=FetchStatus.DISALLOWED, fetcher=self.name)

        polite_delay(self.min_delay, self.max_delay)

        try:
            self._ensure_browser()
            assert self._browser is not None
            context = self._browser.new_context(
                user_agent=self.user_agent,
                locale="en-SA",
                viewport={"width": 1366, "height": 900},
            )
            page = context.new_page()
            try:
                response = page.goto(
                    url, wait_until=self.wait_until, timeout=self.timeout_ms
                )
                http_status = response.status if response else None
                html = page.content()
            finally:
                context.close()
        except PWTimeoutError as exc:
            logger.error("Playwright timeout for %s: %s", url, exc)
            return FetchResult(
                url=url, status=FetchStatus.ERROR, fetcher=self.name, error=str(exc)
            )
        except Exception as exc:  # noqa: BLE001
            logger.error("Playwright error for %s: %s", url, exc)
            return FetchResult(
                url=url, status=FetchStatus.ERROR, fetcher=self.name, error=str(exc)
            )

        if looks_blocked(html, http_status):
            logger.warning("Detected block/challenge for %s (Playwright).", url)
            return FetchResult(
                url=url,
                status=FetchStatus.BLOCKED,
                html=html,
                http_status=http_status,
                fetcher=self.name,
            )

        status = FetchStatus.OK if (http_status or 200) < 400 else FetchStatus.ERROR
        return FetchResult(
            url=url,
            status=status,
            html=html,
            http_status=http_status,
            fetcher=self.name,
        )

    def close(self) -> None:
        try:
            if self._browser is not None:
                self._browser.close()
            if self._pw is not None:
                self._pw.stop()
        except Exception:  # noqa: BLE001 - best-effort cleanup
            logger.debug("Error during Playwright cleanup", exc_info=True)
        finally:
            self._browser = None
            self._pw = None
