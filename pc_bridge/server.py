import os
import sys
import json
import time
import hmac
import socket
import random
import hashlib
import sqlite3
from datetime import datetime, date
from typing import Optional, List, Dict, Any
from contextlib import contextmanager

from fastapi import FastAPI, HTTPException, Query
from fastapi.middleware.cors import CORSMiddleware
from fastapi.staticfiles import StaticFiles
from fastapi.responses import FileResponse
from pydantic import BaseModel

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
DB_PATH = os.path.join(BASE_DIR, "wakeguard.db")
STATIC_DIR = os.path.join(BASE_DIR, "static")

app = FastAPI(
    title="WakeGuard — Barcode & PC 6-Digit Challenge Alarm Hub",
    description="Companion PC Challenge Terminal, Volume Guard Monitor & LAN/Offline TOTP Synchronizer for the WakeGuard Android App",
    version="1.0.0",
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


@contextmanager
def get_conn():
    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row
    try:
        yield conn
    finally:
        conn.close()


def get_lan_ip() -> str:
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
            s.connect(("8.8.8.8", 80))
            return s.getsockname()[0]
    except Exception:
        return "127.0.0.1"


def compute_offline_totp(secret_key: str, timestamp: Optional[float] = None, interval: int = 60) -> Dict[str, Any]:
    """
    Deterministic 6-digit Challenge Code generator shared between the Android app and PC Hub.
    Works 100% offline using HMAC-SHA256 over 60-second epochs so that even if Wi-Fi drops,
    the phone can still validate the 6-digit number or wait for the PC verification token!
    """
    now = int(timestamp if timestamp is not None else time.time())
    epoch = now // interval
    seconds_remaining = interval - (now % interval)

    msg = f"WAKEGUARD-CHALLENGE-{epoch}".encode("utf-8")
    digest = hmac.new(secret_key.encode("utf-8"), msg, hashlib.sha256).hexdigest()
    code_int = int(digest[:8], 16) % 1000000
    code_str = f"{code_int:06d}"

    # Secondary unlock confirmation code (in case user wants to type a response code back into phone)
    ack_msg = f"WAKEGUARD-UNLOCK-ACK-{epoch}-{code_str}".encode("utf-8")
    ack_digest = hmac.new(secret_key.encode("utf-8"), ack_msg, hashlib.sha256).hexdigest()
    ack_code = f"{int(ack_digest[:8], 16) % 1000000:06d}"

    return {
        "epoch": epoch,
        "pc_challenge_code": code_str,
        "phone_offline_unlock_code": ack_code,
        "seconds_remaining": seconds_remaining,
    }


def init_db():
    with get_conn() as conn:
        conn.execute(
            """CREATE TABLE IF NOT EXISTS config (
                key TEXT PRIMARY KEY,
                value TEXT
            )"""
        )
        conn.execute(
            """CREATE TABLE IF NOT EXISTS alarms (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                label TEXT NOT NULL,
                time_24h TEXT NOT NULL,
                days_json TEXT NOT NULL DEFAULT '["Mon","Tue","Wed","Thu","Fri","Sat","Sun"]',
                enabled INTEGER NOT NULL DEFAULT 1,
                dismiss_mode TEXT NOT NULL DEFAULT 'BARCODE_OR_PC',
                target_barcode TEXT NOT NULL DEFAULT '012345678905',
                target_barcode_label TEXT NOT NULL DEFAULT 'Bathroom Toothpaste / Coffee Tin',
                volume_guard_pct INTEGER NOT NULL DEFAULT 100,
                dnd_bypass INTEGER NOT NULL DEFAULT 1,
                progressive_siren INTEGER NOT NULL DEFAULT 1,
                created_at TEXT NOT NULL
            )"""
        )
        conn.execute(
            """CREATE TABLE IF NOT EXISTS active_session (
                id INTEGER PRIMARY KEY CHECK (id = 1),
                is_ringing INTEGER NOT NULL DEFAULT 0,
                alarm_id INTEGER,
                alarm_label TEXT DEFAULT 'Morning WakeGuard Protocol',
                started_at TEXT,
                live_challenge_code TEXT,
                target_barcode TEXT DEFAULT '012345678905',
                target_barcode_label TEXT DEFAULT 'Bathroom Toothpaste / Coffee Tin',
                volume_locked_at_pct INTEGER DEFAULT 100,
                override_events_count INTEGER DEFAULT 0,
                dismissed_at TEXT,
                dismiss_method TEXT
            )"""
        )
        conn.execute(
            """CREATE TABLE IF NOT EXISTS RegisteredBarcodes (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                label TEXT NOT NULL,
                barcode_value TEXT NOT NULL,
                format TEXT NOT NULL DEFAULT 'EAN_13 / UPC_A',
                location_hint TEXT NOT NULL DEFAULT 'Bathroom Sink',
                created_at TEXT NOT NULL
            )"""
        )
        conn.execute(
            """CREATE TABLE IF NOT EXISTS wake_history (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                date TEXT NOT NULL,
                triggered_at TEXT NOT NULL,
                dismissed_at TEXT NOT NULL,
                duration_seconds INTEGER NOT NULL,
                dismiss_method TEXT NOT NULL,
                code_or_barcode_used TEXT NOT NULL,
                volume_tamper_attempts INTEGER NOT NULL DEFAULT 0,
                alarm_label TEXT NOT NULL
            )"""
        )

        # Seed default shared secret
        row = conn.execute("SELECT value FROM config WHERE key = 'shared_secret'").fetchone()
        if not row:
            conn.execute(
                "INSERT INTO config (key, value) VALUES ('shared_secret', 'NX-WAKEGUARD-2026-MASTER-KEY')"
            )

        # Ensure single active_session row exists
        sess = conn.execute("SELECT id FROM active_session WHERE id = 1").fetchone()
        if not sess:
            code = f"{random.randint(100000, 999999)}"
            conn.execute(
                """INSERT INTO active_session
                   (id, is_ringing, alarm_id, alarm_label, started_at, live_challenge_code, target_barcode, target_barcode_label, volume_locked_at_pct, override_events_count)
                   VALUES (1, 0, 1, '06:30 AM — Get Out Of Bed Protocol', NULL, ?, '012345678905', 'Bathroom Toothpaste Barcode', 100, 0)""",
                (code,),
            )

        # Seed default alarms if empty
        alarm_count = conn.execute("SELECT COUNT(*) FROM alarms").fetchone()[0]
        if alarm_count == 0:
            now_str = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
            conn.execute(
                """INSERT INTO alarms
                   (label, time_24h, days_json, enabled, dismiss_mode, target_barcode, target_barcode_label, volume_guard_pct, dnd_bypass, progressive_siren, created_at)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                (
                    "Morning Get-Out-Of-Bed Protocol",
                    "06:30",
                    json.dumps(["Mon", "Tue", "Wed", "Thu", "Fri"]),
                    1,
                    "BARCODE_OR_PC",
                    "012345678905",
                    "Bathroom Toothpaste / Coffee Canister",
                    100,
                    1,
                    1,
                    now_str,
                ),
            )
            conn.execute(
                """INSERT INTO alarms
                   (label, time_24h, days_json, enabled, dismiss_mode, target_barcode, target_barcode_label, volume_guard_pct, dnd_bypass, progressive_siren, created_at)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                (
                    "Weekend Deep Work Wakeup",
                    "08:00",
                    json.dumps(["Sat", "Sun"]),
                    1,
                    "PC_6_DIGIT_ONLY",
                    "9780201379624",
                    "Desk Book ISBN Barcode",
                    100,
                    1,
                    1,
                    now_str,
                ),
            )

        # Seed default registered barcodes if empty
        bc_count = conn.execute("SELECT COUNT(*) FROM RegisteredBarcodes").fetchone()[0]
        if bc_count == 0:
            now_str = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
            conn.execute(
                "INSERT INTO RegisteredBarcodes (label, barcode_value, format, location_hint, created_at) VALUES (?, ?, ?, ?, ?)",
                ("Colgate Toothpaste Tube", "012345678905", "UPC_A", "Bathroom Medicine Cabinet", now_str),
            )
            conn.execute(
                "INSERT INTO RegisteredBarcodes (label, barcode_value, format, location_hint, created_at) VALUES (?, ?, ?, ?, ?)",
                ("Espresso Roast Coffee Tin", "041383090718", "UPC_A", "Kitchen Counter", now_str),
            )
            conn.execute(
                "INSERT INTO RegisteredBarcodes (label, barcode_value, format, location_hint, created_at) VALUES (?, ?, ?, ?, ?)",
                ("Nexus Desk QR Tag", "NEXUS-WAKEGUARD-DESK-01", "QR_CODE", "PC Monitor Stand", now_str),
            )

        # Seed sample wake history if empty
        hist_count = conn.execute("SELECT COUNT(*) FROM wake_history").fetchone()[0]
        if hist_count == 0:
            conn.execute(
                """INSERT INTO wake_history
                   (date, triggered_at, dismissed_at, duration_seconds, dismiss_method, code_or_barcode_used, volume_tamper_attempts, alarm_label)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
                (
                    date.today().isoformat(),
                    "06:30:00",
                    "06:31:18",
                    78,
                    "PC_6_DIGIT_CHALLENGE",
                    "849204",
                    2,
                    "Morning Get-Out-Of-Bed Protocol",
                ),
            )
        conn.commit()


@app.on_event("startup")
def on_startup():
    init_db()


def _get_shared_secret(conn) -> str:
    row = conn.execute("SELECT value FROM config WHERE key = 'shared_secret'").fetchone()
    return row["value"] if row else "NX-WAKEGUARD-2026-MASTER-KEY"


@app.get("/api/status")
def api_get_status():
    with get_conn() as conn:
        secret = _get_shared_secret(conn)
        totp = compute_offline_totp(secret)
        session = dict(conn.execute("SELECT * FROM active_session WHERE id = 1").fetchone())
        alarms = [dict(r) for r in conn.execute("SELECT * FROM alarms ORDER BY time_24h ASC").fetchall()]
        for a in alarms:
            try:
                a["days"] = json.loads(a.get("days_json") or "[]")
            except Exception:
                a["days"] = []
        barcodes = [dict(r) for r in conn.execute("SELECT * FROM RegisteredBarcodes ORDER BY id DESC").fetchall()]
        history = [dict(r) for r in conn.execute("SELECT * FROM wake_history ORDER BY id DESC LIMIT 20").fetchall()]

    return {
        "service": "WakeGuard PC Challenge Hub",
        "lan_ip": get_lan_ip(),
        "port": 5175,
        "shared_secret": secret,
        "totp": totp,
        "active_session": session,
        "alarms": alarms,
        "barcodes": barcodes,
        "history": history,
        "volume_guard_architecture": {
            "stream": "AudioAttributes.USAGE_ALARM (STREAM_ALARM)",
            "enforcer_interval_ms": 500,
            "locks_max_volume": True,
            "blocks_hardware_volume_down": True,
            "bypass_dnd_policy": True,
            "wake_lock": "PARTIAL_WAKE_LOCK + turnScreenOn + showWhenLocked",
        },
        "timestamp": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
    }


class TriggerAlarmRequest(BaseModel):
    alarm_id: Optional[int] = 1
    alarm_label: Optional[str] = "Morning Get-Out-Of-Bed Protocol"
    challenge_code: Optional[str] = None
    target_barcode: Optional[str] = "012345678905"
    target_barcode_label: Optional[str] = "Bathroom Toothpaste Barcode"
    volume_locked_at_pct: int = 100


@app.post("/api/session/trigger")
def api_trigger_alarm(payload: TriggerAlarmRequest):
    """
    Called by the Android app when AlarmManager fires the Full-Screen Intent (or triggered from PC for testing).
    Generates or registers a random 6-digit number that MUST be typed on the computer to turn off the alarm.
    """
    code = payload.challenge_code or f"{random.randint(100000, 999999)}"
    if len(code) != 6 or not code.isdigit():
        code = f"{random.randint(100000, 999999)}"

    now_str = datetime.now().strftime("%H:%M:%S")
    with get_conn() as conn:
        conn.execute(
            """UPDATE active_session
               SET is_ringing = 1,
                   alarm_id = ?,
                   alarm_label = ?,
                   started_at = ?,
                   live_challenge_code = ?,
                   target_barcode = ?,
                   target_barcode_label = ?,
                   volume_locked_at_pct = ?,
                   override_events_count = 0,
                   dismissed_at = NULL,
                   dismiss_method = NULL
               WHERE id = 1""",
            (
                payload.alarm_id,
                payload.alarm_label,
                now_str,
                code,
                payload.target_barcode,
                payload.target_barcode_label,
                payload.volume_locked_at_pct,
            ),
        )
        conn.commit()
    return api_get_status()


@app.post("/api/session/volume-tamper")
def api_record_volume_tamper():
    """Logged whenever the user tries to press Volume Down while the alarm is ringing and VolumeEnforcer snaps it back to 100%."""
    with get_conn() as conn:
        conn.execute(
            "UPDATE active_session SET override_events_count = override_events_count + 1 WHERE id = 1"
        )
        conn.commit()
    return api_get_status()


class VerifyCodeRequest(BaseModel):
    code: str
    method: str = "PC_6_DIGIT_CHALLENGE"  # PC_6_DIGIT_CHALLENGE or MLKIT_BARCODE_SCAN


@app.post("/api/session/verify")
def api_verify_and_dismiss(payload: VerifyCodeRequest):
    """
    Verifies the 6-digit code typed on the computer OR the barcode scanned by ML Kit on the phone.
    If valid, silences the active alarm session and records the wake event in wake_history.
    """
    submitted = payload.code.strip()
    with get_conn() as conn:
        secret = _get_shared_secret(conn)
        totp = compute_offline_totp(secret)
        prev_totp = compute_offline_totp(secret, timestamp=time.time() - 60)
        session = dict(conn.execute("SELECT * FROM active_session WHERE id = 1").fetchone())
        barcodes = [
            r["barcode_value"]
            for r in conn.execute("SELECT barcode_value FROM RegisteredBarcodes").fetchall()
        ]

        live_code = session.get("live_challenge_code") or ""
        target_bc = session.get("target_barcode") or ""

        is_valid = False
        matched_method = payload.method

        if submitted and (
            submitted == live_code
            or submitted == totp["pc_challenge_code"]
            or submitted == prev_totp["pc_challenge_code"]
        ):
            is_valid = True
            matched_method = "PC_6_DIGIT_CHALLENGE"
        elif submitted and (submitted == target_bc or submitted in barcodes):
            is_valid = True
            matched_method = "MLKIT_BARCODE_SCAN"

        if not is_valid:
            raise HTTPException(
                status_code=400,
                detail=f"Invalid 6-digit code or barcode '{submitted}'. Check your phone screen or scan a registered barcode!",
            )

        now_str = datetime.now().strftime("%H:%M:%S")
        started_str = session.get("started_at") or now_str
        duration_sec = 45
        try:
            t1 = datetime.strptime(started_str, "%H:%M:%S")
            t2 = datetime.strptime(now_str, "%H:%M:%S")
            duration_sec = max(1, int((t2 - t1).total_seconds()))
        except Exception:
            pass

        conn.execute(
            """UPDATE active_session
               SET is_ringing = 0,
                   dismissed_at = ?,
                   dismiss_method = ?
               WHERE id = 1""",
            (now_str, matched_method),
        )
        conn.execute(
            """INSERT INTO wake_history
               (date, triggered_at, dismissed_at, duration_seconds, dismiss_method, code_or_barcode_used, volume_tamper_attempts, alarm_label)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
            (
                date.today().isoformat(),
                started_str,
                now_str,
                duration_sec,
                matched_method,
                submitted,
                int(session.get("override_events_count") or 0),
                session.get("alarm_label") or "WakeGuard Alarm",
            ),
        )
        conn.commit()

    return {
        "ok": True,
        "dismissed": True,
        "method": matched_method,
        "phone_offline_unlock_code": totp["phone_offline_unlock_code"],
        "status": api_get_status(),
    }


class AlarmCreateRequest(BaseModel):
    label: str
    time_24h: str
    days: List[str] = ["Mon", "Tue", "Wed", "Thu", "Fri"]
    dismiss_mode: str = "BARCODE_OR_PC"
    target_barcode: str = "012345678905"
    target_barcode_label: str = "Bathroom Toothpaste Barcode"
    volume_guard_pct: int = 100
    dnd_bypass: int = 1
    progressive_siren: int = 1


@app.post("/api/alarms")
def api_create_alarm(payload: AlarmCreateRequest):
    now_str = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    with get_conn() as conn:
        conn.execute(
            """INSERT INTO alarms
               (label, time_24h, days_json, enabled, dismiss_mode, target_barcode, target_barcode_label, volume_guard_pct, dnd_bypass, progressive_siren, created_at)
               VALUES (?, ?, ?, 1, ?, ?, ?, ?, ?, ?, ?)""",
            (
                payload.label,
                payload.time_24h,
                json.dumps(payload.days),
                payload.dismiss_mode,
                payload.target_barcode,
                payload.target_barcode_label,
                payload.volume_guard_pct,
                payload.dnd_bypass,
                payload.progressive_siren,
                now_str,
            ),
        )
        conn.commit()
    return api_get_status()


@app.post("/api/alarms/{alarm_id}/toggle")
def api_toggle_alarm(alarm_id: int):
    with get_conn() as conn:
        row = conn.execute("SELECT enabled FROM alarms WHERE id = ?", (alarm_id,)).fetchone()
        if not row:
            raise HTTPException(status_code=404, detail="Alarm not found")
        new_val = 0 if row["enabled"] else 1
        conn.execute("UPDATE alarms SET enabled = ? WHERE id = ?", (new_val, alarm_id))
        conn.commit()
    return api_get_status()


@app.delete("/api/alarms/{alarm_id}")
def api_delete_alarm(alarm_id: int):
    with get_conn() as conn:
        conn.execute("DELETE FROM alarms WHERE id = ?", (alarm_id,))
        conn.commit()
    return api_get_status()


class BarcodeRegisterRequest(BaseModel):
    label: str
    barcode_value: str
    format: str = "UPC_A"
    location_hint: str = "Bathroom / Kitchen"


@app.post("/api/barcodes")
def api_register_barcode(payload: BarcodeRegisterRequest):
    now_str = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    with get_conn() as conn:
        conn.execute(
            "INSERT INTO RegisteredBarcodes (label, barcode_value, format, location_hint, created_at) VALUES (?, ?, ?, ?, ?)",
            (payload.label, payload.barcode_value.strip(), payload.format, payload.location_hint, now_str),
        )
        conn.commit()
    return api_get_status()


@app.delete("/api/barcodes/{barcode_id}")
def api_delete_barcode(barcode_id: int):
    with get_conn() as conn:
        conn.execute("DELETE FROM RegisteredBarcodes WHERE id = ?", (barcode_id,))
        conn.commit()
    return api_get_status()


@app.get("/")
def serve_index():
    return FileResponse(os.path.join(STATIC_DIR, "index.html"))


app.mount("/static", StaticFiles(directory=STATIC_DIR), name="static")

if __name__ == "__main__":
    import uvicorn

    port = int(os.environ.get("WAKEGUARD_PORT", "5175"))
    print("=" * 68)
    print(f"  WakeGuard PC Challenge Hub & Android Sync Server (Port {port})")
    print(f"  - Local / Nexus OS Embed URL : http://localhost:{port}")
    print(f"  - LAN Phone Sync Endpoint    : http://{get_lan_ip()}:{port}")
    print("=" * 68)
    uvicorn.run(app, host="0.0.0.0", port=port)
