"""Offline tests for the CarLife HU emulator, branch B discovery (UDP beacon + 8999 probe): local loopback only.

    python -m pytest pc-headunit/tests/test_carlife_beacon.py
"""
from __future__ import annotations

import socket
import sys
import tempfile
import threading
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

import carlife_hu as C                                           # noqa: E402
from test_carlife_tcp6 import CHANS, FakePhone                   # noqa: E402


def make_hu(**kw) -> C.HeadUnit:
    out = Path(tempfile.mkdtemp(prefix="carlife_hu_beacon_test_"))
    return C.HeadUnit(C.Options(out=out, quiet=True, transport="tcp6", discover=True, bind_ip="127.0.0.1",
                                beacon_port=0, **kw))


def send_udp(port: int, payload: bytes = b"CarlifeHost") -> int:
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.bind(("127.0.0.1", 0))
        s.sendto(payload, ("127.0.0.1", port))
        return s.getsockname()[1]
    finally:
        s.close()


# ---- Options / argparse ----

def test_options_discover_validation():
    with pytest.raises(ValueError):
        C.Options(transport="stream", discover=True)
    with pytest.raises(ValueError):
        C.Options(transport="tcp6", probe_8999=True)
    o = C.Options(transport="tcp6", discover=True)
    assert (o.beacon_port, o.bind_ip, o.discover_timeout, o.probe_port) == (7999, "0.0.0.0", 60.0, 8999)


def test_parse_args_discover():
    a = C.parse_args(["--transport", "tcp6", "--discover", "--probe-8999", "--discover-timeout", "5",
                      "--beacon-port", "1234", "--bind-ip", "192.168.43.2"])
    assert a.discover and a.probe_8999 and a.discover_timeout == 5 and a.beacon_port == 1234
    assert a.bind_ip == "192.168.43.2"
    with pytest.raises(SystemExit):
        C.parse_args(["--discover"])                             # without tcp6
    with pytest.raises(SystemExit):
        C.parse_args(["--transport", "tcp6", "--probe-8999"])    # without --discover


# ---- wait_beacon ----

def test_wait_beacon_returns_sender():
    hu = make_hu()
    hu.beacon_listen()
    try:
        assert hu.beacon_port != 0
        sport = send_udp(hu.beacon_port)
        assert hu.wait_beacon(3.0) == ("127.0.0.1", sport)
        assert hu.beacons == 1
    finally:
        hu.bsock.close()


def test_wait_beacon_timeout():
    hu = make_hu()
    hu.beacon_listen()
    try:
        assert hu.wait_beacon(0.3) is None
        assert hu.beacons == 0
    finally:
        hu.bsock.close()


def test_discover_timeout_stop_reason():
    hu = make_hu(discover_timeout=0.3)
    res = hu.run()
    assert res["stop_reason"] == "no beacon within 0.3 s"
    assert res["discovered_host"] is None and res["beacons"] == 0 and res["probe_reply"] is None
    assert res["connected"] == 0
    assert "no beacon within 0.3 s" in (hu.o.out / "carlife_hu.log").read_text(encoding="utf-8")


# ---- end-to-end: beacon -> sample -> 6 socket ----

def test_discover_session():
    phone = FakePhone()
    probe = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)    # phone on 8999 (ephemeral port)
    probe.bind(("127.0.0.1", 0))
    probe.settimeout(5)
    probed: list[bytes] = []

    def answer():
        data, addr = probe.recvfrom(4096)
        probed.append(data)
        probe.sendto(b'{"carlifehost":"carlife","ok":1}', addr)

    pt = threading.Thread(target=answer, daemon=True)
    pt.start()
    hu = make_hu(duration=20.0, probe_8999=True, probe_port=probe.getsockname()[1], extra_data_socket=False)
    hu.o.ports = phone.ports
    hu.beacon_listen()
    res: dict = {}
    t = threading.Thread(target=lambda: res.update(hu.run()), daemon=True)
    t.start()
    try:
        send_udp(hu.beacon_port)
        phone.accept_all()
        # CMD: naked HU_PROTOCOL_VERSION, without external header
        assert phone.pump(C.CH_CMD, lambda m: len(m) >= 1)
        assert phone.types(C.CH_CMD) == [C.HU_PROTOCOL_VERSION]
        assert phone.parsers[C.CH_CMD].buf == b""
        for s in phone.conns.values():
            s.close()
        t.join(10)
        assert not t.is_alive()
        pt.join(5)
    finally:
        phone.close()
        probe.close()
    assert probed == [C.PROBE_REQUEST]
    assert res["discovered_host"] == "127.0.0.1" and res["beacons"] >= 1 and res["probe_reply"] is True
    assert res["transport"] == "tcp6" and res["connected"] == len(CHANS)
    log = (hu.o.out / "carlife_hu.log").read_text(encoding="utf-8")
    assert "beacon from 127.0.0.1:" in log and "payload=CarlifeHost" in log
    assert f"probe {hu.o.probe_port}: reply " in log
    assert "discover: host 127.0.0.1, beacons" in log


def test_discover_probe_no_reply_still_connects():
    phone = FakePhone()
    silent = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)   # port is available, no answer
    silent.bind(("127.0.0.1", 0))
    hu = make_hu(duration=20.0, probe_8999=True, probe_port=silent.getsockname()[1])
    hu.o.ports = phone.ports
    hu.PROBE_WAIT = 0.3
    hu.beacon_listen()
    res: dict = {}
    t = threading.Thread(target=lambda: res.update(hu.run()), daemon=True)
    t.start()
    try:
        send_udp(hu.beacon_port, b"something else")
        phone.accept_all()
        for s in phone.conns.values():
            s.close()
        t.join(15)
        assert not t.is_alive()
    finally:
        phone.close()
        silent.close()
    assert res["probe_reply"] is False and res["connected"] == len(CHANS)
    assert "payload=other" in (hu.o.out / "carlife_hu.log").read_text(encoding="utf-8")
