"""Fetcher abstraction shared by the Requests and Playwright backends."""

from __future__ import annotations

import abc
import enum
from dataclasses import dataclass, field


class FetchStatus(enum.StrEnum):
    OK = "ok"
    NOT_FOUND = "not_found"
    BLOCKED = "blocked"          # 403/429/anti-bot challenge
    ERROR = "error"              # transient/network/other
    DISALLOWED = "disallowed"    # robots.txt forbids this path


class BlockedError(RuntimeError):
    """Raised (or signalled via status) when a host actively blocks us.

    We never try to evade anti-bot protection — on a block we reduce frequency
    and log, per project policy.
    """


@dataclass(slots=True)
class FetchResult:
    """The outcome of a single fetch attempt."""

    url: str
    status: FetchStatus
    html: str = ""
    http_status: int | None = None
    fetcher: str = ""            # which backend produced this
    final_url: str | None = None
    error: str | None = None
    meta: dict[str, str] = field(default_factory=dict)

    @property
    def ok(self) -> bool:
        return self.status is FetchStatus.OK and bool(self.html)


class Fetcher(abc.ABC):
    """Abstract base class for all fetchers.

    Concrete implementations must be usable as context managers so network
    resources (sessions, browsers) are released deterministically.
    """

    name: str = "base"

    @abc.abstractmethod
    def fetch(self, url: str) -> FetchResult:
        """Fetch a single URL and return a :class:`FetchResult`."""

    def close(self) -> None:  # pragma: no cover - default no-op
        """Release any held resources. Overridden where relevant."""

    def __enter__(self) -> "Fetcher":
        return self

    def __exit__(self, *exc: object) -> None:
        self.close()
