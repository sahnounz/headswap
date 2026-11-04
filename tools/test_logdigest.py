"""Digest tests on synthetic mini-log: python -X utf8 -m unittest tools/test_logdigest.py"""
import os
import sys
import tempfile
import unittest
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import logdigest  # noqa: E402

DEVICE = """Headswap 1.1.6
Время: 2026-10-12 10:00:00 +0300
Устройство: Google Pixel 8, Android 16 (SDK 36), BP1A
Аптайм: 1000 с

Настройки моста
  видео: перекодирование
  сертификат: hur

Данные ГУ и состояние
протокол: ГУ шлёт версию 2.0
auth: -
"""

LOG = """[... начало обрезано, показаны последние 4096 КиБ ...]
broken partial line
09:00:00.000 == USB_ACCESSORY_ATTACHED, в intent: manufacturer="Baidu" model="CarLife"
09:00:01.000 сессия: старт
09:00:01.100 RX CMD 0x00018043 CARLIFE_DATA_SUBSCRIBE len=8 {cnt=2 subscribemobileCarLifeInfo={moduleID=0} subscribemobileCarLifeInfo={moduleID=1}} [resv=0]
09:00:01.200 RX CMD 0x00018045 CARLIFE_DATA_SUBSCRIBE_START len=8 {cnt=1 subscribemobileCarLifeInfo={moduleID=0}} [resv=0]
09:00:01.300 == ГУ ПРОСИТ РАЗРЕШЕНИЕ: 1280x720 fps=30
09:00:01.400 видео-режим R720: AA 1280x720 margins 0x0 density 160, CarLife 1280x720, без кропа
09:00:02.000 AA TCP подключено 127.0.0.1:5288
09:00:02.100 AA RX AUDIO_MEDIA(6) CHANNEL_OPEN_REQUEST 0x0007 len=4 | {priority=0 service_id=6}
09:00:02.200 AA RX MIC(7) CHANNEL_OPEN_REQUEST 0x0007 len=4 | {priority=0 service_id=7}
09:00:02.300 AA RX MEDIA_PLAYBACK(9) MEDIA_START 0x8001 len=20 | {session_id=1 configuration_index="YandexMusic"}
09:00:02.400 AA RX VIDEO(2) MEDIA_START 0x8001 len=4 | {session_id=2 configuration_index=0}
09:00:02.900 AA телефон закрыл соединение (EOF)
09:00:02.901 AA итог: кадров rx=10 tx=12; видео config=0 data=0 байт=0; аудио data=0; причина: peer closed: телефон закрыл соединение (EOF)
09:00:03.000 RAW 00 11 22
    0000  00 11 22 33 44 55 66 77  88 99 AA BB CC DD EE FF  |................|
09:00:05.000 BRIDGE видео[перекодирование]: AA вход 30,0 fps, выход в CarLife 29,8 fps (цель 30), отброшено энкодер/гейт 1/0; транскод
09:00:10.000 BRIDGE видео[перекодирование]: AA вход 30,0 fps, выход в CarLife 30,0 fps, отброшено энкодер/гейт 0/0
09:00:15.000 BRIDGE видео[перекодирование]: AA вход 10,0 fps, выход в CarLife 10,0 fps, отброшено энкодер/гейт 0/0
09:00:16.000 видео: отправлено кадров=19 (ключевых=1) байт=1000 heartbeat=0 сброшено=0 очередь=0 (+19 за 2448634с)
09:00:21.000 видео: отправлено кадров=169 (ключевых=5) байт=9000 heartbeat=0 сброшено=0 очередь=0 (+150 за 5с)
09:00:22.000 RX TOUCH 0x00068001 TOUCH_ACTION len=12 {action=0 x=1 y=2} [resv=0]
09:00:22.100 RX TOUCH 0x00068001 TOUCH_ACTION len=12 {action=1 x=1 y=2} [resv=0]
09:00:23.000 AA TCP подключено 127.0.0.1:5288
09:00:23.100 AA RX VIDEO(2) MEDIA_START 0x8001 len=4 | {session_id=2 configuration_index=0}
09:00:23.200 AA RX VIDEO(2) MEDIA_DATA 0x0000 len=100
09:00:23.300 AA RX MIC(7) MIC_REQUEST 0x8005 len=2 | {open=true}
09:00:23.310 AA RX NAVIGATION(10) MIC_REQUEST 0x8005 len=2 | {f1=1}
09:00:24.000 AA телефон закрыл соединение (EOF)
09:00:24.001 AA итог: кадров rx=50 tx=50; видео config=0 data=1 байт=100; аудио data=0; причина: peer closed: телефон закрыл соединение (EOF)
09:00:30.000 ERROR ошибка чтения: IOException: read failed: EIO (I/O error)
09:00:30.100 == РЕЗЮМЕ СЕССИИ (29 с, причина: ошибка чтения: read failed: EIO (I/O error))
  принято байт: 4144
  версия протокола ГУ: 2.0
  HU_AUTHEN_REQUEST: ПРИШЁЛ randomValue=2.0;abc
  HU_AUTHEN_RESULT: не приходил
  запрошенное разрешение: 1280x720 fps=30; ответ: 1280x720 (мост: режим R720)
  тач: TOUCH_ACTION (прозрачный, TRANSPARENT_SEND_TOUCH_EVENT=true), событий=5
  видео: кадров=170 ключевых=5 heartbeat=0 сброшено=0
  HU_INFO: {os="Linux" manufacturer="Honda" model="CR-V" product=""}
    0010  00 11 22 33 44 55 66 77  88 99 AA BB CC DD EE FF  |................|
09:00:30.200 сессия: закрыта
09:00:31.000 USB_ACCESSORY_DETACHED
09:00:32.000 AA TX CONTROL(0) BYEBYE_REQUEST 0x000f len=2 | {reason=1}
09:00:33.000 AUTOSRV запуск: USB подключён
09:00:34.000 AUTOSRV шаг 3: кнопка «Ещё» не найдена (попытка 1)
09:00:35.000 AUTOSRV итог: NOT_FOUND: меню сервера ГУ не открылось (7039 мс)
09:00:36.000 ERROR ошибка чтения: IOException: read failed: EIO (I/O error)
"""

LOGCAT = "10-12 09:01:00.000  1000  1000 I ActivityManager: act=android.intent.action.ACTION_POWER_DISCONNECTED\n"


class DigestTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        d = self.tmp.name
        os.makedirs(os.path.join(d, "logs"))
        with open(os.path.join(d, "device.txt"), "w", encoding="utf-8") as f:
            f.write(DEVICE)
        with open(os.path.join(d, "logs", "headswap-20261012-085900.log"), "w", encoding="utf-8") as f:
            f.write(LOG)
        with open(os.path.join(d, "logcat-20261012-090200.txt"), "w", encoding="utf-8") as f:
            f.write(LOGCAT)
        self.zip = os.path.join(d, "headswap-20261012-100000.zip")
        with zipfile.ZipFile(self.zip, "w") as z:
            z.write(os.path.join(d, "device.txt"), "device.txt")
            z.write(os.path.join(d, "logs", "headswap-20261012-085900.log"), "logs/headswap-20261012-085900.log")
        self.dir = d

    def tearDown(self):
        self.tmp.cleanup()

    def test_zip(self):
        r = logdigest.digest(self.zip)
        env = r["env"]
        self.assertEqual(env["headswap"], "1.1.6")
        self.assertEqual(env["android"], "16")
        self.assertEqual(env["hu"], ["Honda CR-V x1"])
        self.assertEqual(env["protocol"], "2.0")
        self.assertTrue(env["auth"].startswith("есть"))
        self.assertEqual(r["truncated"], ["logs/headswap-20261012-085900.log"])

        (s,) = r["sessions"]
        self.assertEqual(s["start"], "09:00:00")
        self.assertEqual(s["start_src"], "USB_ACCESSORY_ATTACHED")
        self.assertEqual(s["dur_s"], 29)
        self.assertEqual(s["frames"], 170)
        self.assertEqual(s["mode"], "R720")
        self.assertEqual(s["aa_sessions"], 2)
        self.assertEqual(s["aa"], "rx=50 config=0 data=1")
        self.assertIn("EIO", s["reason"])

        ep = r["episodes"]
        self.assertEqual(ep["counts"]["EOF"], 2)
        self.assertEqual(ep["counts"]["EIO"], 2)
        self.assertEqual(ep["counts"]["USB_ACCESSORY_DETACHED"], 1)
        self.assertEqual(ep["counts"]["BYEBYE_REQUEST"], 1)
        # first EOF: config=0, data=0, 500 ms after VIDEO MEDIA_START; second: data=1 is not a flag
        self.assertEqual(ep["no_gms_flags"], 1)
        self.assertIn("через 500 мс после MEDIA_START VIDEO(2)", "\n".join(ep["clusters"]))

        v = r["video"]
        self.assertEqual(v["out"]["n"], 3)
        self.assertEqual(v["out"]["median"], 29.8)
        self.assertEqual(v["sent"], {"median": 30.0, "min": 30.0, "max": 30.0, "n": 1})
        self.assertEqual(v["dips_total"], 1)

        er = r["errors"]["top"][0]
        self.assertEqual((er["n"], er["first"], er["last"]), (2, "09:00:30", "09:00:36"))

        a = r["autosrv"]
        self.assertEqual(a["not_found"], 1)
        self.assertEqual(a["triggers"], {"USB подключён": 1})

        au = r["audio"]
        self.assertEqual(au["channels_opened"], {"AUDIO_MEDIA(6)": 1, "MIC(7)": 1})
        self.assertEqual(au["media_start"], None)  # MEDIA_PLAYBACK is not considered
        self.assertEqual(au["mic_request_aa"], 1)  # NAVIGATION(10) MIC_REQUEST not microphone

        n = r["navi"]
        self.assertEqual((n["subscribe_0x18043"], n["start_0x18045"]), (1, 1))
        self.assertEqual(n["subscribe_modules"], ["0", "1"])
        self.assertEqual(n["aa_navigation_ch"], {"MIC_REQUEST": 1})

        self.assertEqual(r["touch"]["touch_action_lines"], 2)
        self.assertEqual(r["touch"]["resume_events_sum"], 5)
        self.assertIn("[9] Тач", logdigest.render(r))

    def test_dir_with_logcat(self):
        r = logdigest.digest(self.dir)
        self.assertEqual(r["episodes"]["counts"].get("POWER_DISCONNECTED"), 1)
        self.assertIn("зажигание", "\n".join(r["episodes"]["clusters"]))

    def test_garbage(self):
        p = os.path.join(self.dir, "junk.log")
        with open(p, "wb") as f:
            f.write(b"\xff\xfe garbage\n12:00 not a line\n\x00\x01\n")
        r = logdigest.digest(p)
        text = logdigest.render(r)
        for key in ("sessions", "video", "errors", "autosrv", "audio", "navi", "touch"):
            self.assertFalse(r[key], key)
        self.assertGreaterEqual(text.count(logdigest.NO_DATA), 8)


if __name__ == "__main__":
    unittest.main()
