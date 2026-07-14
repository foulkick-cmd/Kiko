"""Tests for the category and product parsers against synthetic fixtures."""

from __future__ import annotations

from deal_tracker.models import SellerType
from deal_tracker.parsers import CategoryParser, ProductParser

from .conftest import load_fixture


def test_category_parser_nextdata():
    refs = CategoryParser().parse(load_fixture("noon_category_nextdata.html"))
    by_id = {r.product_id: r for r in refs}
    assert "N53504907A" in by_id
    assert "Z1B2C3D4E5" in by_id
    iphone = by_id["N53504907A"]
    assert iphone.current_price == 2999.0
    assert iphone.before_price == 3499.0
    assert iphone.seller_name == "noon"
    assert iphone.product_url.endswith("/N53504907A/p/")


def test_category_parser_jsonld():
    refs = CategoryParser().parse(load_fixture("noon_category_jsonld.html"))
    by_id = {r.product_id: r for r in refs}
    assert by_id["XM13NOTE99"].current_price == 799.0
    assert by_id["ANKPC20000"].title == "Anker PowerCore 20000"
    # image given as a list should still resolve to a string
    assert by_id["ANKPC20000"].image.endswith(".jpg")


def test_product_parser_jsonld_noon_seller():
    product = ProductParser().parse(
        load_fixture("noon_product_jsonld.html"),
        product_url="https://www.noon.com/saudi-en/x/N53504907A/p/",
        category="Mobiles",
    )
    assert product is not None
    assert product.product_id == "N53504907A"
    assert product.current_price == 2999.0
    assert product.before_price == 3499.0
    assert product.rating == 4.6
    assert product.review_count == 1203
    assert product.seller_type is SellerType.NOON
    # displayed discount derived from before/current when absent
    assert product.displayed_discount_percent == round((1 - 2999 / 3499) * 100, 2)
    assert product.is_complete()


def test_product_parser_nextdata_marketplace_seller():
    product = ProductParser().parse(
        load_fixture("noon_product_nextdata.html"),
        product_url="https://www.noon.com/saudi-en/x/Z1B2C3D4E5/p/",
    )
    assert product is not None
    assert product.product_id == "Z1B2C3D4E5"
    assert product.current_price == 2450.0
    assert product.before_price == 4100.0
    assert product.seller_type is SellerType.MARKETPLACE
    assert product.seller_name == "Best Electronics Store"


def test_product_parser_returns_none_on_garbage():
    assert ProductParser().parse("<html><body>nothing</body></html>") is None
