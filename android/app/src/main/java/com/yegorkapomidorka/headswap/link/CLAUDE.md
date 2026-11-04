# link/: head unit transport

See `docs/ARCHITECTURE.md` for the application map and `docs/adr/` for design decisions (wireless: ADR 0014).

- `StreamTransport` handles USB AOA and debug TCP: one stream with an outer header; write bodies in chunks ≤ 16 KiB (`Wire.CHUNK`).
  Mobis CCNC sends the body in a single write (ADR 0009). EIO in the log comes from IOException text here.
- `MultiSocketTransport` handles wireless: the phone listens on six ports and the head unit connects as a client,
  with a separate socket for each channel.
- Three paths place the phone and head unit on the same network: `bt/` (RFCOMM `carlife` and joining the head unit's P2P group),
  `wifi/HotspotBeacon` (phone hotspot, UDP 7999), and `car/CarNetwork` (car Wi-Fi). The bridge does not enable the hotspot itself.
- USB takes priority over wireless (`BridgeService`). Mode selection is pure logic in `car/CarLogic`, covered by tests.
- Never write passwords, SSIDs, or keys to `WLog`.
