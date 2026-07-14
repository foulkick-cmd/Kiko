"""Telegram alerting.

Sends a cleanly formatted deal alert with an optional 90-day price chart. Uses
the Telegram Bot HTTP API directly via ``requests`` (no extra dependency).

Set ``TELEGRAM_BOT_TOKEN`` and ``TELEGRAM_CHAT_ID`` (env or config) to enable.
"""

from __future__ import annotations

import html
import logging

import requests

from ..models import DealAnalysis, Product

logger = logging.getLogger(__name__)

_API = "https://api.telegram.org"


def _fmt_price(value: float | None, currency: str) -> str:
    return f"{value:,.2f} {currency}" if value is not None else "—"


def build_alert_text(product: Product, analysis: DealAnalysis) -> str:
    """Build an HTML-formatted Telegram message body for a deal.

    Includes: title, current/previous/lowest/avg prices, displayed discount,
    confidence label, seller and URL. The image and chart are sent separately.
    """
    stats = analysis.stats
    cur = product.currency
    conf = analysis.confidence
    lines = [
        f"<b>{html.escape(product.title)}</b>",
        "",
        f"{conf.emoji} <b>{conf.value} confidence</b> · "
        f"score {analysis.score:.2f} · <i>{analysis.discount_class.value}</i>",
        "",
        f"💰 Now: <b>{_fmt_price(product.current_price, cur)}</b>",
        f"↩️ Previous: {_fmt_price(product.before_price, cur)}",
        f"📉 90d low: {_fmt_price(stats.min_price_90d, cur)}",
        f"📊 90d avg: {_fmt_price(stats.avg_price_90d, cur)}",
    ]
    if product.displayed_discount_percent is not None:
        lines.append(f"🏷️ Displayed discount: {product.displayed_discount_percent:.0f}%")
    lines += [
        "",
        f"🛍️ Seller: {html.escape(product.seller_name or 'Unknown')} "
        f"({product.seller_type.value})",
    ]
    if product.rating is not None:
        rc = f" ({product.review_count})" if product.review_count else ""
        lines.append(f"⭐ Rating: {product.rating:g}{rc}")
    if product.product_url:
        lines += ["", f'<a href="{html.escape(product.product_url)}">View on store »</a>']
    return "\n".join(lines)


class TelegramAlerter:
    """Thin wrapper over the Telegram Bot API for sending deal alerts."""

    def __init__(
        self,
        bot_token: str | None,
        chat_id: str | None,
        *,
        timeout: float = 20.0,
    ) -> None:
        self.bot_token = bot_token
        self.chat_id = chat_id
        self.timeout = timeout
        self.enabled = bool(bot_token and chat_id)
        if not self.enabled:
            logger.info("Telegram alerter disabled (missing token/chat id).")

    def send_deal(
        self,
        product: Product,
        analysis: DealAnalysis,
        *,
        chart_path: str | None = None,
    ) -> bool:
        """Send a deal alert. Returns True on success (or dry-run when disabled)."""
        text = build_alert_text(product, analysis)
        if not self.enabled:
            logger.info("[dry-run] Would send Telegram alert:\n%s", text)
            return True

        try:
            if chart_path:
                return self._send_photo(chart_path, caption=text)
            if product.product_image:
                return self._send_photo_url(product.product_image, caption=text)
            return self._send_message(text)
        except requests.RequestException as exc:
            logger.error("Telegram send failed for %s: %s", product.product_id, exc)
            return False

    # ------------------------------------------------------------------ #
    def _send_message(self, text: str) -> bool:
        resp = requests.post(
            f"{_API}/bot{self.bot_token}/sendMessage",
            data={
                "chat_id": self.chat_id,
                "text": text,
                "parse_mode": "HTML",
                "disable_web_page_preview": "false",
            },
            timeout=self.timeout,
        )
        return self._ok(resp)

    def _send_photo(self, path: str, *, caption: str) -> bool:
        with open(path, "rb") as fh:
            resp = requests.post(
                f"{_API}/bot{self.bot_token}/sendPhoto",
                data={"chat_id": self.chat_id, "caption": caption, "parse_mode": "HTML"},
                files={"photo": fh},
                timeout=self.timeout,
            )
        return self._ok(resp)

    def _send_photo_url(self, url: str, *, caption: str) -> bool:
        resp = requests.post(
            f"{_API}/bot{self.bot_token}/sendPhoto",
            data={"chat_id": self.chat_id, "photo": url, "caption": caption,
                  "parse_mode": "HTML"},
            timeout=self.timeout,
        )
        return self._ok(resp)

    @staticmethod
    def _ok(resp: requests.Response) -> bool:
        if resp.status_code == 200 and resp.json().get("ok"):
            return True
        logger.error("Telegram API error %s: %s", resp.status_code, resp.text[:300])
        return False
