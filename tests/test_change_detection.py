"""Tests for alert change detection and duplicate suppression."""

from __future__ import annotations

from deal_tracker.alerts.change_detection import AlertState, should_alert


def _state(tmp_path):
    return AlertState(str(tmp_path / "alert_state.json"))


def test_alert_on_price_drop(tmp_path):
    state = _state(tmp_path)
    d = should_alert("P1", 90.0, 100.0, state=state)
    assert d.should_send is True


def test_no_alert_when_not_lower(tmp_path):
    state = _state(tmp_path)
    assert should_alert("P1", 100.0, 100.0, state=state).should_send is False
    assert should_alert("P1", 110.0, 100.0, state=state).should_send is False


def test_no_alert_without_prior_price(tmp_path):
    state = _state(tmp_path)
    assert should_alert("P1", 90.0, None, state=state).should_send is False


def test_min_drop_threshold(tmp_path):
    state = _state(tmp_path)
    # 2% drop, threshold 10% -> no alert
    assert should_alert("P1", 98.0, 100.0, state=state, min_drop_percent=10).should_send is False
    # 20% drop, threshold 10% -> alert
    assert should_alert("P1", 80.0, 100.0, state=state, min_drop_percent=10).should_send is True


def test_duplicate_suppression(tmp_path):
    state = _state(tmp_path)
    d1 = should_alert("P1", 90.0, 100.0, state=state)
    assert d1.should_send is True
    state.record("P1", 90.0)
    # same price again -> duplicate, suppressed
    d2 = should_alert("P1", 90.0, 100.0, state=state)
    assert d2.should_send is False
    # a further drop -> alert again
    d3 = should_alert("P1", 85.0, 90.0, state=state)
    assert d3.should_send is True


def test_state_persistence_roundtrip(tmp_path):
    path = tmp_path / "alert_state.json"
    s1 = AlertState(str(path))
    s1.record("P1", 42.0)
    s1.save()
    s2 = AlertState(str(path))
    assert s2.last_alerted_price("P1") == 42.0
