#!/usr/bin/env python3
"""Headswap: log digest summary on the 1-2 screen instead of raw megabytes.

Run: python -X utf8 tools/logdigest.py <zip|folder|file.log> [--json]

Input: zip from application (device.txt + logs/headswap-*.log or probe-*.log, sometimes
logcat-*.txt), a folder with logs removed via adb, or a separate log. Only stdlib.
Each section experiences a lack of data and writes "no data".
"""
import json
import os
import re
import statistics
import sys
import zipfile
from collections import Counter
from datetime import datetime, timedelta

NO_DATA = "нет данных"

TS_RE = re.compile(r"^(\d\d):(\d\d):(\d\d)\.(\d{3}) (.*)$")
LOGCAT_RE = re.compile(r"^(\d\d)-(\d\d) (\d\d):(\d\d):(\d\d)\.(\d{3})\s+(.*)$")
STAMP_RE = re.compile(r"(\d{8})-(\d{6})")

AA_MSG_RE = re.compile(r"^AA (RX|TX) ([A-Z_]+)\((\d+)\) ([A-Z_0-9]+)")
CL_MSG_RE = re.compile(r"^(RX|TX) (\S+) (0x[0-9A-Fa-f]+)\S* ([A-Z_0-9]+)")
AA_SUMMARY_RE = re.compile(
    r"AA итог: кадров rx=(\d+) tx=(\d+); видео config=(\d+) data=(\d+)(?: байт=(\d+))?"
    r"(?:; аудио data=(\d+))?(?:; причина: (.*))?")
RESUME_RE = re.compile(r"^== РЕЗЮМЕ СЕССИИ \((\d+) с, причина: (.*)\)\s*$")
BRIDGE_FPS_RE = re.compile(
    r"^BRIDGE видео\[([^\]]+)\]: AA вход ([\d.,]+) fps, выход в CarLife ([\d.,]+) fps"
    r"(?: \([^)]*\))?, отброшено энкодер/гейт (\d+)/(\d+)")
SENT_RE = re.compile(r"^видео: отправлено кадров=(\d+).*\(\+(\d+) за (\d+)с\)")
MODE_RE = re.compile(r"^видео-режим (\S+): (.*)$")
HU_INFO_RE = re.compile(r'manufacturer="([^"]*)".*?model="([^"]*)"')
SUB_IDS_RE = re.compile(r"moduleID=(\d+)")


def norm(s, limit=110):
    s = re.sub(r"0x[0-9A-Fa-f]+", "0xN", s)
    s = re.sub(r"\d+", "N", s)
    s = re.sub(r"\s+", " ", s).strip()
    return s[:limit]


def fnum(s):
    return float(s.replace(",", "."))


def short_reason(r):
    r = r or ""
    for k in ("ENODEV", "EIO", "EOF", "EPIPE", "ETIMEDOUT"):
        if k in r:
            head = r.split(":", 1)[0]
            return f"{head}: {k}"
    return r[:40] if r else "?"


# ---------------------------------------------- input

def read_file(p):
    with open(p, "rb") as f:
        return f.read()


def load_sources(path):
    """-> (device_txt | None, [(name, text)] logs, [(name, text)] logcat)."""
    files = []  # (name, bytes)
    if os.path.isdir(path):
        for root, _, names in os.walk(path):
            for n in names:
                p = os.path.join(root, n)
                files.append((os.path.relpath(p, path), read_file(p)))
    elif zipfile.is_zipfile(path):
        with zipfile.ZipFile(path) as z:
            files = [(i.filename, z.read(i)) for i in z.infolist() if not i.is_dir()]
    else:
        files.append((os.path.basename(path), read_file(path)))

    device, logs, logcats = None, [], []
    for name, data in files:
        base = os.path.basename(name).lower()
        text = lambda: data.decode("utf-8", errors="replace")  # noqa: E731
        if base == "device.txt":
            device = text()
        elif base.startswith("logcat") and base.endswith(".txt"):
            logcats.append((name, text()))
        elif base.endswith(".log") or (len(files) == 1 and base.endswith(".txt")):
            logs.append((name, text()))
    logs.sort(key=lambda x: (stamp_of(x[0]) or datetime.min, x[0]))
    return device, logs, logcats


def stamp_of(name):
    m = STAMP_RE.search(os.path.basename(name))
    if not m:
        return None
    try:
        return datetime.strptime(m.group(1) + m.group(2), "%Y%m%d%H%M%S")
    except ValueError:
        return None


# ---------------------------------------------

class Clock:
    """Line time HH:MM:SS.mmm -> datetime with a date from the file name and going through midnight."""

    def __init__(self, stamp):
        self.base = (stamp or datetime(1970, 1, 1)).replace(hour=0, minute=0, second=0, microsecond=0)
        self.stamp = stamp
        self.prev = None

    def at(self, h, m, s, ms):
        t = self.base + timedelta(hours=h, minutes=m, seconds=s, milliseconds=ms)
        if self.prev is None:
            if self.stamp and t < self.stamp - timedelta(minutes=1):
                self.base += timedelta(days=1)
                t += timedelta(days=1)
        elif t < self.prev - timedelta(hours=6):
            self.base += timedelta(days=1)
            t += timedelta(days=1)
        self.prev = t
        return t


class AaSess:
    def __init__(self, t):
        self.start = t
        self.cfg = 0
        self.vdata = 0
        self.last_media_start = None  # (t, channel)


class Digest:
    def __init__(self):
        self.files = []
        self.truncated = []
        self.hu = Counter()
        self.proto = None
        self.auth = []
        self.sessions = []
        self.cur = None
        self.resume_target = None
        self.last_attach = None
        self.last_mode = None
        self.aa = None
        self.aa_summaries = []
        self.episodes = []
        self.fps = []  # (t, mode, input, output, enc, gate)
        self.sent = []  # (t, fps)
        self.errors = {}
        self.autosrv = {"triggers": Counter(), "results": Counter(), "steps": Counter(), "skips": 0, "failures": 0}
        self.ch_open = Counter()
        self.media_start = Counter()
        self.aa_cnt = Counter()
        self.cl_cnt = Counter()
        self.nav_aa = Counter()
        self.sub_modules = set()
        self.sub_start_modules = set()
        self.voice_bridge = 0
        self.resume_touch = 0
        self.lines = 0

    # --- sessions

    def new_session(self, t, start_src):
        s = {"start": t, "start_src": start_src, "end": None, "dur_s": None, "reason": None,
             "frames": None, "requested": None, "answer": None, "mode": self.last_mode,
             "touch": None, "aa": [], "hu": None}
        self.sessions.append(s)
        return s

    def session_for(self, t):
        for s in reversed(self.sessions):
            if s["start"] is None or s["start"] <= t:
                return s
        return self.sessions[0] if self.sessions else None

    # --- file string

    def episode(self, t, kind, detail=""):
        self.episodes.append({"t": t, "kind": kind, "detail": detail})

    def error(self, t, text):
        key = norm(text)
        e = self.errors.get(key)
        if e is None:
            self.errors[key] = {"n": 1, "first": t, "last": t, "sample": text[:160]}
        else:
            e["n"] += 1
            e["last"] = t

    def resume_field(self, line):
        s = self.resume_target
        if s is None or ":" not in line:
            return
        k, v = line.strip().split(":", 1)
        v = v.strip()
        if k == "версия протокола ГУ":
            self.proto = v
        elif k == "HU_AUTHEN_REQUEST":
            self.auth.append(v)
        elif k == "запрошенное разрешение":
            req, _, ans = v.partition("; ответ:")
            s["requested"] = req.strip()
            s["answer"] = ans.strip() or None
        elif k == "видео":
            m = re.search(r"кадров=(\d+)", v)
            if m:
                s["frames"] = int(m.group(1))
        elif k == "тач":
            m = re.search(r"событий=(\d+)", v)
            if m:
                s["touch"] = int(m.group(1))
                self.resume_touch += int(m.group(1))
        elif k == "HU_INFO":
            m = HU_INFO_RE.search(v)
            if m:
                hu = f"{m.group(1)} {m.group(2)}".strip() or "пустой HU_INFO"
                s["hu"] = hu
                self.hu[hu] += 1

    def feed_log(self, name, text):
        clock = Clock(stamp_of(name))
        self.files.append(name)
        in_resume = False
        self.orphan_ok = False
        for i, raw in enumerate(text.splitlines()):
            if i == 0 and raw.startswith("[... начало обрезано"):
                self.truncated.append(name)
                self.orphan_ok = True
                continue
            m = TS_RE.match(raw)
            if not m:
                if in_resume and raw.startswith("  ") and not raw.startswith("   "):
                    self.resume_field(raw)
                continue
            in_resume = False
            self.lines += 1
            t = clock.at(int(m.group(1)), int(m.group(2)), int(m.group(3)), int(m.group(4)))
            body = m.group(5)
            if body.startswith("RAW ") or body.startswith("  ") and not body.strip():
                continue
            try:
                in_resume = self.feed_line(t, body)
            except Exception as e:  # string of unexpected format should not drop digest
                self.error(t, f"logdigest: не разобрана строка ({type(e).__name__})")

    def feed_line(self, t, body):
        """True, if the RESUME starts (the next lines with indentation are its fields)."""
        if body.startswith("AA "):
            self.feed_aa(t, body)
            return False
        if body.startswith("== "):
            mr = RESUME_RE.match(body)
            if mr:
                s = self.cur or self.new_session(None, "обрезано")
                s["dur_s"] = int(mr.group(1))
                s["reason"] = mr.group(2)
                s["end"] = t
                if s["start"] is None:
                    s["start_est"] = t - timedelta(seconds=s["dur_s"])
                self.resume_target = s
                self.cur = None
                self.orphan_ok = False
                return True
            if body.startswith("== USB_ACCESSORY_ATTACHED"):
                self.last_attach = t
            elif body.startswith("== HU_INFO:"):
                mh = HU_INFO_RE.search(body)
                if mh and self.cur is not None and not self.cur["hu"]:
                    self.cur["hu"] = f"{mh.group(1)} {mh.group(2)}".strip()
            elif body.startswith("== ВЕРСИЯ ПРОТОКОЛА ГУ:"):
                mv = re.search(r"major=(\d+) minor=(\d+)", body)
                if mv:
                    self.proto = f"{mv.group(1)}.{mv.group(2)}"
            elif body.startswith("== ГУ ПРОСИТ РАЗРЕШЕНИЕ:") and self.cur is not None:
                self.cur["requested"] = body.split(":", 1)[1].strip()
            elif body.startswith("== ОТВЕЧАЮ INIT_DONE") and self.cur is not None:
                self.cur["answer"] = body[len("== ОТВЕЧАЮ INIT_DONE"):].strip()
            elif body.startswith("== SUBSCRIBE_START") or body.startswith("== CARLIFE_DATA_SUBSCRIBE"):
                tgt = self.sub_start_modules if "SUBSCRIBE_START" in body else self.sub_modules
                mm = re.search(r"moduleID=\[([^\]]*)\]", body)
                if mm:
                    tgt.update(x.strip() for x in mm.group(1).split(",") if x.strip())
            return False
        if body == "сессия: старт":
            self.orphan_ok = False
            if self.cur is not None:
                self.cur["reason"] = self.cur["reason"] or "без резюме"
            if self.last_attach and timedelta(0) <= t - self.last_attach <= timedelta(seconds=10):
                self.cur = self.new_session(self.last_attach, "USB_ACCESSORY_ATTACHED")
            else:
                self.cur = self.new_session(t, "старт")
            return False
        mm = MODE_RE.match(body)
        if mm:
            self.last_mode = mm.group(1)
            if self.cur is not None:
                self.cur["mode"] = mm.group(1)
            return False
        ms = SENT_RE.match(body)
        if ms:
            if self.cur is None and self.orphan_ok:
                # the file is cropped in the middle of the session, and the resume never appeared in it
                self.cur = self.new_session(None, "обрезано")
                self.cur["start_est"] = t
            self.orphan_ok = False
            if self.cur is not None:
                self.cur["frames"] = int(ms.group(1))
            sec = int(ms.group(3))
            if 3 <= sec <= 15:
                self.sent.append((t, int(ms.group(2)) / sec))
            return False
        if body.startswith("BRIDGE"):
            mf = BRIDGE_FPS_RE.match(body)
            if mf:
                self.fps.append((t, mf.group(1), fnum(mf.group(2)), fnum(mf.group(3)),
                                 int(mf.group(4)), int(mf.group(5))))
            elif body.startswith("BRIDGE голосовая сессия: старт"):
                self.voice_bridge += 1
            return False
        if body.startswith("AUTOSRV"):
            self.feed_autosrv(body)
            if body.startswith("AUTOSRV сбой"):
                self.error(t, body)
            return False
        if body.startswith("ERROR "):
            msg = body[6:]
            self.error(t, msg)
            for k in ("ENODEV", "EIO", "EPIPE"):
                if k in msg:
                    self.episode(t, k, msg[:90])
                    break
            return False
        if body.startswith("USB_ACCESSORY_DETACHED"):
            self.episode(t, "USB_ACCESSORY_DETACHED")
            return False
        mc = CL_MSG_RE.match(body)
        if mc:
            self.feed_carlife(mc.group(1), mc.group(3), mc.group(4), body)
            return False
        if "Exception" in body:
            self.error(t, body)
        return False

    def feed_aa(self, t, body):
        if body.startswith("AA TCP подключено") or body.startswith("AA сессия установлена") and self.aa is None:
            self.aa = AaSess(t)
            return
        if body.startswith("AA итог:"):
            m = AA_SUMMARY_RE.match(body)
            if m:
                a = {"t": t, "rx": int(m.group(1)), "tx": int(m.group(2)), "config": int(m.group(3)),
                     "data": int(m.group(4)), "reason": m.group(7) or ""}
                self.aa_summaries.append(a)
                s = self.session_for(self.aa.start if self.aa else t)
                if s is not None:
                    s["aa"].append(a)
            self.aa = None
            return
        if body.startswith("AA телефон закрыл соединение"):
            if "ByeBye" in body:
                self.episode(t, "EOF", "после ByeBye")
                return
            detail = ""
            a = self.aa
            if a and a.cfg == 0 and a.vdata == 0 and a.last_media_start:
                dt = (t - a.last_media_start[0]).total_seconds() * 1000
                if dt <= 5000:
                    detail = (f"через {dt:.0f} мс после MEDIA_START {a.last_media_start[1]} при config=0"
                              " — сигнатура телефона без GMS")
            self.episode(t, "EOF", detail)
            return
        if body.startswith("AA завершаю: peer closed") and "EOF" not in body:
            self.episode(t, "peer closed", body.split("peer closed:", 1)[1].strip()[:80])
            return
        m = AA_MSG_RE.match(body)
        if not m:
            if "Exception" in body:
                self.error(t, body)
            return
        d, ch, chn, msg = m.groups()
        chan = f"{ch}({chn})"
        if ch == "NAVIGATION":
            self.nav_aa[msg] += 1
            return
        if d == "RX" and msg == "CHANNEL_OPEN_REQUEST":
            self.ch_open[chan] += 1
        elif d == "RX" and msg == "MEDIA_START" and ch != "MEDIA_PLAYBACK":
            self.media_start[chan] += 1
            if self.aa:
                self.aa.last_media_start = (t, chan)
        elif msg == "MEDIA_CODEC_CONFIG" and ch == "VIDEO" and self.aa:
            self.aa.cfg += 1
        elif msg == "MEDIA_DATA" and ch == "VIDEO" and d == "RX" and self.aa:
            self.aa.vdata += 1
        elif msg in ("MIC_REQUEST", "MICROPHONE_REQUEST") and ch == "MIC":
            self.aa_cnt["MIC_REQUEST"] += 1
        elif msg.startswith("VOICE_SESSION") or msg.startswith("AUDIO_FOCUS"):
            self.aa_cnt[f"{msg} {d}"] += 1
        elif msg == "BYEBYE_REQUEST":
            self.episode(t, "BYEBYE_REQUEST", "от телефона" if d == "RX" else "от моста")

    def feed_carlife(self, d, code, name, body):
        c = code.lower().lstrip("0x").lstrip("0")
        if name == "CARLIFE_DATA_SUBSCRIBE" or c == "18043":
            self.cl_cnt["CARLIFE_DATA_SUBSCRIBE"] += 1
            self.sub_modules.update(SUB_IDS_RE.findall(body))
        elif name == "CARLIFE_DATA_SUBSCRIBE_START" or c == "18045":
            self.cl_cnt["CARLIFE_DATA_SUBSCRIBE_START"] += 1
            self.sub_start_modules.update(SUB_IDS_RE.findall(body))
        elif name in ("NAVI_NEXT_TURN_INFO", "TOUCH_ACTION", "TOUCH_CAR_HARD_KEY_CODE") or name.startswith("MIC_RECORD"):
            self.cl_cnt[name] += 1
        elif name.startswith("HU_AUTHEN"):
            self.cl_cnt[name] += 1

    def feed_autosrv(self, body):
        a = self.autosrv
        if body.startswith("AUTOSRV запуск:"):
            a["triggers"][body.split(":", 1)[1].strip()[:50]] += 1
        elif body.startswith("AUTOSRV итог:"):
            a["results"][norm(body.split(":", 1)[1], 70)] += 1
        elif re.match(r"AUTOSRV шаг \d+", body):
            a["steps"][norm(body[len("AUTOSRV "):], 70)] += 1
        elif body.startswith("AUTOSRV пропуск"):
            a["skips"] += 1
        elif body.startswith("AUTOSRV сбой"):
            a["failures"] += 1

    def feed_logcat(self, name, text):
        year = (stamp_of(name) or datetime.now()).year
        for raw in text.splitlines():
            if "POWER_DISCONNECTED" not in raw and "POWER_CONNECTED" not in raw:
                continue
            m = LOGCAT_RE.match(raw)
            if not m:
                continue
            try:
                t = datetime(year, int(m.group(1)), int(m.group(2)), int(m.group(3)),
                             int(m.group(4)), int(m.group(5)), int(m.group(6)) * 1000)
            except ValueError:
                continue
            if "POWER_DISCONNECTED" in raw:
                self.episode(t, "POWER_DISCONNECTED", "зажигание")
            else:
                self.episode(t, "POWER_CONNECTED", "зажигание вкл")


# --------------------------------------------------- summary

def parse_device(text):
    if not text:
        return {}
    d = {"settings": {}}
    section = None
    for line in text.splitlines():
        if not line.strip():
            continue
        if line.startswith("  "):
            if section == "settings" and ":" in line:
                k, v = line.strip().split(":", 1)
                d["settings"][k.strip()] = v.strip()
            continue
        section = "settings" if line.startswith("Настройки моста") else None
        if line.startswith("Headswap "):
            d["version"] = line[len("Headswap "):].strip()
        elif ":" in line:
            k, v = line.split(":", 1)
            k, v = k.strip(), v.strip()
            if k == "Время":
                d["time"] = v
            elif k == "Устройство":
                m = re.match(r"(.*?), Android (\S+)(?: \(SDK (\d+)\))?", v)
                if m:
                    d["device"], d["android"], d["sdk"] = m.group(1), m.group(2), m.group(3)
                else:
                    d["device"] = v
            elif k in ("протокол", "auth", "статус", "AA cert", "AA кадров", "МОСТ", "AUTOSRV", "видео", "тач"):
                d[k] = v
    return d


def fmt_t(t, multi_day):
    if t is None:
        return "—"
    return t.strftime("%d.%m %H:%M:%S" if multi_day else "%H:%M:%S")


def build(dg, device_txt, logcats):
    times = [s["start"] for s in dg.sessions if s["start"]] + [e["t"] for e in dg.episodes]
    multi_day = len({t.date() for t in times}) > 1
    ft = lambda t: fmt_t(t, multi_day)
    out = {"files": dg.files, "truncated": dg.truncated, "lines": dg.lines}

    def section(key, fn):
        try:
            out[key] = fn()
        except Exception as e:
            out[key] = {"error": f"ошибка разбора: {type(e).__name__}: {e}"}

    def env():
        d = parse_device(device_txt)
        proto = dg.proto
        if not proto and d.get("протокол"):
            m = re.search(r"версию (\S+)", d["протокол"])
            proto = m.group(1) if m else d["протокол"]
        auth_seen = [a for a in dg.auth if a and not a.startswith("не приходил")]
        if auth_seen:
            auth = "есть: " + auth_seen[-1][:60]
        elif dg.cl_cnt.get("HU_AUTHEN_REQUEST"):
            auth = "есть (HU_AUTHEN_REQUEST в логе)"
        elif dg.auth:
            auth = "нет (HU_AUTHEN_REQUEST не приходил)"
        elif d.get("auth"):
            auth = "device.txt: " + d["auth"]
        else:
            auth = None
        st = d.get("settings", {})
        return {
            "headswap": d.get("version"), "time": d.get("time"), "device": d.get("device"),
            "android": d.get("android"), "sdk": d.get("sdk"),
            "hu": [f"{k} x{v}" for k, v in dg.hu.most_common()] or None,
            "protocol": proto, "auth": auth,
            "settings": {k: st[k] for k in st if k in (
                "видео", "разрешение видео", "частота кадров", "звук по USB", "сертификат", "микрофон",
                "режим моста", "автозапуск сервера ГУ")} or None,
            "status": d.get("статус"),
        }

    def sessions():
        rows = []
        for s in dg.sessions:
            aa = s["aa"]
            main = max(aa, key=lambda a: a["data"]) if aa else None
            start = s["start"] or s.get("start_est")
            rows.append({
                "start": ft(start) + ("" if s["start"] else "~"), "start_src": s["start_src"],
                "dur_s": s["dur_s"], "reason": s["reason"] or "лог кончился", "frames": s["frames"],
                "aa": (f"rx={main['rx']} config={main['config']} data={main['data']}" if main else None),
                "aa_sessions": len(aa), "requested": s["requested"], "answer": s["answer"],
                "mode": s["mode"], "hu": s["hu"], "touch": s["touch"],
            })
        return rows

    def episodes():
        ev = sorted(dg.episodes, key=lambda e: e["t"])
        counts = Counter(e["kind"] for e in ev)
        gms = [e for e in ev if "без GMS" in e["detail"]]
        clusters = []
        for e in ev:
            if clusters and (e["t"] - clusters[-1][-1]["t"]).total_seconds() <= 15:
                clusters[-1].append(e)
            else:
                clusters.append([e])
        lines = []
        for c in clusters:
            t0 = c[0]["t"]
            parts = []
            for e in c:
                dt = (e["t"] - t0).total_seconds()
                p = e["kind"] + (f"+{dt:.1f}с" if dt >= 0.5 else "")
                if e["detail"] and e["kind"] not in ("ENODEV", "EIO", "EPIPE"):
                    p += f" ({e['detail']})"
                parts.append(p)
            lines.append(f"{ft(t0)} " + ", ".join(parts))
        return {"counts": dict(counts), "no_gms_flags": len(gms), "clusters": lines}

    def video():
        if not dg.fps and not dg.sent:
            return None
        r = {}

        def stats(xs):
            return {"median": round(statistics.median(xs), 1), "min": round(min(xs), 1),
                    "max": round(max(xs), 1), "n": len(xs)}

        if dg.fps:
            r["modes"] = dict(Counter(f[1] for f in dg.fps))
            r["aa_in"] = stats([f[2] for f in dg.fps])
            r["out"] = stats([f[3] for f in dg.fps])
            r["dropped_max"] = f"{max(f[4] for f in dg.fps)}/{max(f[5] for f in dg.fps)}"
            series = [(f[0], f[3]) for f in dg.fps]
        else:
            series = dg.sent
        if dg.sent:
            r["sent"] = stats([x[1] for x in dg.sent])
        med = statistics.median(v for _, v in series)
        dips, cur = [], None
        for t, v in series:
            if v < med * 0.5:
                if cur and (t - cur["to"]).total_seconds() <= 15:
                    cur["to"], cur["min"], cur["n"] = t, min(cur["min"], v), cur["n"] + 1
                else:
                    cur = {"from": t, "to": t, "min": v, "n": 1}
                    dips.append(cur)
            else:
                cur = None
        r["dips_total"] = len(dips)
        r["dips"] = [f"{ft(d['from'])}" + (f"–{ft(d['to'])}" if d["n"] > 1 else "") + f" мин {d['min']:.1f}"
                     for d in dips[:8]]
        return r

    def errors():
        if not dg.errors:
            return None
        top = sorted(dg.errors.values(), key=lambda e: -e["n"])
        return {"distinct": len(top), "top": [
            {"n": e["n"], "first": ft(e["first"]), "last": ft(e["last"]), "msg": e["sample"][:110]}
            for e in top[:8]]}

    def autosrv():
        a = dg.autosrv
        if not (a["triggers"] or a["results"] or a["steps"] or a["skips"]):
            return None
        nf = sum(n for k, n in a["results"].items() if "NOT_FOUND" in k)
        return {"triggers": dict(a["triggers"].most_common(5)), "results": dict(a["results"].most_common(5)),
                "not_found": nf, "steps": dict(a["steps"].most_common(4)), "skips": a["skips"],
                "failures": a["failures"]}

    def audio():
        keep = ("AUDIO_MEDIA", "AUDIO_SPEECH", "AUDIO_SYSTEM", "MIC")
        opened = {k: v for k, v in dg.ch_open.items() if k.split("(")[0] in keep}
        started = {k: v for k, v in dg.media_start.items() if k.split("(")[0] in keep}
        mic_rec = {k: v for k, v in dg.cl_cnt.items() if k.startswith("MIC_RECORD")}
        r = {"channels_opened": opened or None, "media_start": started or None,
             "mic_request_aa": dg.aa_cnt.get("MIC_REQUEST", 0),
             "voice_session": sum(v for k, v in dg.aa_cnt.items() if k.startswith("VOICE_SESSION")),
             "voice_bridge": dg.voice_bridge,
             "audio_focus": {k: v for k, v in dg.aa_cnt.items() if k.startswith("AUDIO_FOCUS")} or None,
             "carlife_mic_record": mic_rec or None}
        if not any(v for v in r.values()):
            return None
        return r

    def navi():
        r = {"subscribe_0x18043": dg.cl_cnt.get("CARLIFE_DATA_SUBSCRIBE", 0),
             "subscribe_modules": sorted(dg.sub_modules, key=int) or None,
             "start_0x18045": dg.cl_cnt.get("CARLIFE_DATA_SUBSCRIBE_START", 0),
             "start_modules": sorted(dg.sub_start_modules, key=int) or None,
             "navi_next_turn_info": dg.cl_cnt.get("NAVI_NEXT_TURN_INFO", 0),
             "aa_navigation_ch": dict(dg.nav_aa) or None}
        if not any(v for v in r.values()):
            return None
        return r

    def touch():
        n = dg.cl_cnt.get("TOUCH_ACTION", 0)
        k = dg.cl_cnt.get("TOUCH_CAR_HARD_KEY_CODE", 0)
        if not (n or k or dg.resume_touch):
            return None
        return {"touch_action_lines": n, "resume_events_sum": dg.resume_touch, "hard_keys": k}

    section("env", env)
    section("sessions", sessions)
    section("episodes", episodes)
    section("video", video)
    section("errors", errors)
    section("autosrv", autosrv)
    section("audio", audio)
    section("navi", navi)
    section("touch", touch)
    return out


def digest(path):
    device, logs, logcats = load_sources(path)
    dg = Digest()
    for name, text in logs:
        dg.feed_log(name, text)
    for name, text in logcats:
        dg.feed_logcat(name, text)
    return build(dg, device, logcats)


# -------------------------------------------

def kv(d):
    return ", ".join(f"{k} {v}" for k, v in d.items()) if d else NO_DATA


def render(r):
    L = []
    add = L.append
    tr = f", обрезаны: {len(r['truncated'])}" if r["truncated"] else ""
    add(f"Логи: {', '.join(os.path.basename(f) for f in r['files']) or NO_DATA} ({r['lines']} строк{tr})")

    def err(sec):
        v = r.get(sec)
        if isinstance(v, dict) and "error" in v:
            add("  " + v["error"])
            return True
        return False

    add("\n[1] Окружение")
    e = r.get("env") or {}
    if not err("env"):
        dev = e.get("device") or NO_DATA
        add(f"  Headswap {e.get('headswap') or '?'}; {dev}, Android {e.get('android') or '?'}"
            + (f" (SDK {e['sdk']})" if e.get("sdk") else "") + (f"; снят {e['time']}" if e.get("time") else ""))
        add(f"  ГУ: {', '.join(e['hu']) if e.get('hu') else NO_DATA}; протокол {e.get('protocol') or NO_DATA};"
            f" авторизация: {e.get('auth') or NO_DATA}")
        if e.get("settings"):
            add(("  настройки: " + "; ".join(f"{k}: {v[:45]}" for k, v in e["settings"].items()))[:220])
        if e.get("status"):
            add(f"  статус: {e['status']}")

    add("\n[2] Сессии CarLife")
    ss = r.get("sessions")
    if not err("sessions"):
        if not ss:
            add("  " + NO_DATA)
        else:
            shown = ss[-15:]
            if len(ss) > len(shown):
                add(f"  ... ещё {len(ss) - len(shown)} раньше")
            add("  начало     длит  причина               кадров  AA rx/config/data (n)        запрос -> ответ, режим")
            for s in shown:
                dur = f"{s['dur_s']}с" if s["dur_s"] is not None else "?"
                aa = s["aa"] or "—"
                if s["aa_sessions"] > 1:
                    aa += f" ({s['aa_sessions']})"
                req = s["requested"] or "?"
                ans = s["answer"] or "?"
                mode = s["mode"] or "?"
                add(f"  {s['start']:<9} {dur:>5}  {short_reason(s['reason']):<21} {str(s['frames'] or '—'):>6}  "
                    f"{aa:<28} {req} -> {ans}, {mode}")

    add("\n[3] Обрывы")
    ep = r.get("episodes")
    if not err("episodes"):
        if not ep or not ep["counts"]:
            add("  " + NO_DATA)
        else:
            add("  " + kv(ep["counts"]) + (f"; БЕЗ GMS: {ep['no_gms_flags']}" if ep["no_gms_flags"] else ""))
            cl = ep["clusters"]
            for line in cl[-14:]:
                add("  " + line[:150])
            if len(cl) > 14:
                add(f"  ... ещё {len(cl) - 14} эпизодов раньше")

    add("\n[4] Видео")
    v = r.get("video")
    if not err("video"):
        if not v:
            add("  " + NO_DATA)
        else:
            st = lambda x: f"медиана {x['median']} мин {x['min']} макс {x['max']} (n={x['n']})"
            if v.get("out"):
                add(f"  {kv(v['modes'])}; AA вход {st(v['aa_in'])}")
                add(f"  выход в CarLife {st(v['out'])}; отброшено энк/гейт макс {v['dropped_max']}")
            if v.get("sent"):
                add(f"  отправлено fps: {st(v['sent'])}")
            add(f"  провалы <50% медианы: {v['dips_total']}" + (": " + "; ".join(v["dips"]) if v["dips"] else ""))

    add("\n[5] Ошибки")
    er = r.get("errors")
    if not err("errors"):
        if not er:
            add("  " + NO_DATA)
        else:
            add(f"  разных: {er['distinct']}")
            for x in er["top"]:
                add(f"  {x['n']:>4}x {x['first']}..{x['last']}  {x['msg']}")

    add("\n[6] AUTOSRV")
    a = r.get("autosrv")
    if not err("autosrv"):
        if not a:
            add("  " + NO_DATA)
        else:
            add(f"  запуск: {kv(a['triggers'])}; пропусков {a['skips']}, сбоев {a['failures']}")
            add(f"  итоги (NOT_FOUND {a['not_found']}): " + ("; ".join(f"{k} x{n}" for k, n in a["results"].items()) or NO_DATA))
            if a["steps"]:
                add("  шаги: " + "; ".join(f"{k} x{n}" for k, n in a["steps"].items()))

    add("\n[7] Звук и микрофон")
    au = r.get("audio")
    if not err("audio"):
        if not au:
            add("  " + NO_DATA)
        else:
            add(f"  каналы открыты: {kv(au['channels_opened'])}; MEDIA_START: {kv(au['media_start'])}")
            add(f"  MIC_REQUEST (MIC 7): {au['mic_request_aa']}; VOICE_SESSION: {au['voice_session']};"
                f" голосовая сессия моста: {au['voice_bridge']}; AUDIO_FOCUS: {kv(au['audio_focus'])}")
            if au["carlife_mic_record"]:
                add(f"  CarLife MIC_RECORD: {kv(au['carlife_mic_record'])}")

    add("\n[8] Навигация")
    n = r.get("navi")
    if not err("navi"):
        if not n:
            add("  " + NO_DATA)
        else:
            add(f"  CARLIFE_DATA_SUBSCRIBE 0x18043: {n['subscribe_0x18043']}"
                + (f" модули {','.join(n['subscribe_modules'])}" if n["subscribe_modules"] else "")
                + f"; SUBSCRIBE_START 0x18045: {n['start_0x18045']}"
                + (f" модули {','.join(n['start_modules'])}" if n["start_modules"] else "")
                + f"; NAVI_NEXT_TURN_INFO: {n['navi_next_turn_info']}")
            if n["aa_navigation_ch"]:
                add(f"  AA NAVIGATION(10): {kv(n['aa_navigation_ch'])} (MIC_REQUEST здесь — ошибочная подпись, не микрофон)")

    add("\n[9] Тач")
    tc = r.get("touch")
    if not err("touch"):
        if not tc:
            add("  " + NO_DATA)
        else:
            add(f"  TOUCH_ACTION строк в логе: {tc['touch_action_lines']}; сумма «событий=» из резюме: "
                f"{tc['resume_events_sum']} (разный охват: обрезка лога, сессии без резюме); кнопки руля: {tc['hard_keys']}")
    return "\n".join(L)


def main(argv):
    args = [a for a in argv if not a.startswith("--")]
    if len(args) != 1:
        print(__doc__.strip(), file=sys.stderr)
        return 2
    r = digest(args[0])
    if "--json" in argv:
        print(json.dumps(r, ensure_ascii=False, indent=1, default=str))
    else:
        print(render(r))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
