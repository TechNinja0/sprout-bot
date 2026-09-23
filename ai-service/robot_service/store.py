import hashlib
import json
import os
import secrets
import shutil
import sqlite3
import threading
import time
from contextlib import contextmanager
from pathlib import Path


def uid():
    return secrets.token_hex(16)


def digest(value: str | bytes):
    return hashlib.sha256(
        value.encode() if isinstance(value, str) else value
    ).hexdigest()


def dumps(value):
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"))


SCHEMA = """
CREATE TABLE IF NOT EXISTS meta(key TEXT PRIMARY KEY, value TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS devices(id TEXT PRIMARY KEY, role TEXT NOT NULL, name TEXT NOT NULL,
 token_hash TEXT UNIQUE NOT NULL, robot_id TEXT, revoked INTEGER NOT NULL DEFAULT 0,
 last_seen REAL NOT NULL DEFAULT 0, status TEXT NOT NULL DEFAULT '{}');
CREATE TABLE IF NOT EXISTS invites(hash TEXT PRIMARY KEY, kind TEXT NOT NULL, robot_id TEXT,
 expires REAL NOT NULL, consumed INTEGER NOT NULL DEFAULT 0);
CREATE TABLE IF NOT EXISTS pairs(id TEXT PRIMARY KEY, robot_id TEXT NOT NULL, name TEXT NOT NULL,
 claim_hash TEXT NOT NULL, expires REAL NOT NULL, state TEXT NOT NULL, token_hash TEXT, parent_id TEXT);
CREATE TABLE IF NOT EXISTS configs(robot_id TEXT PRIMARY KEY, version INTEGER NOT NULL, body TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS commands(id TEXT PRIMARY KEY, robot_id TEXT NOT NULL, parent_id TEXT NOT NULL,
 kind TEXT NOT NULL, expected INTEGER NOT NULL, body TEXT NOT NULL, expires REAL NOT NULL,
 state TEXT NOT NULL, created REAL NOT NULL);
CREATE TABLE IF NOT EXISTS resources(id TEXT PRIMARY KEY, kind TEXT NOT NULL, draft_version INTEGER NOT NULL,
 draft TEXT NOT NULL, published_id TEXT, status TEXT NOT NULL, updated REAL NOT NULL);
CREATE TABLE IF NOT EXISTS revisions(id TEXT PRIMARY KEY, resource_id TEXT NOT NULL, body TEXT NOT NULL,
 created REAL NOT NULL, revoked INTEGER NOT NULL DEFAULT 0);
CREATE TABLE IF NOT EXISTS assets(id TEXT PRIMARY KEY, resource_id TEXT NOT NULL, hash TEXT NOT NULL,
 filename TEXT NOT NULL, media_type TEXT NOT NULL, size INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS jobs(id TEXT PRIMARY KEY, resource_id TEXT NOT NULL, asset_id TEXT NOT NULL,
 draft_version INTEGER NOT NULL, state TEXT NOT NULL, result TEXT NOT NULL, error TEXT, created REAL NOT NULL);
CREATE TABLE IF NOT EXISTS receipts(request_id TEXT PRIMARY KEY, owner TEXT NOT NULL, result TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS progress(robot_id TEXT NOT NULL, resource_id TEXT NOT NULL, revision_id TEXT NOT NULL,
 segment_id TEXT NOT NULL, offset_ms INTEGER NOT NULL, seq INTEGER NOT NULL, PRIMARY KEY(robot_id,resource_id));
CREATE TABLE IF NOT EXISTS downloads(robot_id TEXT NOT NULL, resource_id TEXT NOT NULL, revision_id TEXT NOT NULL,
 state TEXT NOT NULL, updated REAL NOT NULL, PRIMARY KEY(robot_id,resource_id));
CREATE TABLE IF NOT EXISTS tombstones(id TEXT PRIMARY KEY, kind TEXT NOT NULL, created REAL NOT NULL);
CREATE TABLE IF NOT EXISTS memories(id TEXT PRIMARY KEY, body TEXT NOT NULL, state TEXT NOT NULL, updated REAL NOT NULL);
CREATE TABLE IF NOT EXISTS playlists(id TEXT PRIMARY KEY, name TEXT NOT NULL, resources TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS usage(robot_id TEXT NOT NULL, day TEXT NOT NULL, seq INTEGER NOT NULL,
 seconds INTEGER NOT NULL, PRIMARY KEY(robot_id,day));
"""


class StorageCapacityError(RuntimeError):
    pass


class ClosingConnection(sqlite3.Connection):
    def __exit__(self, *args):
        try:
            return super().__exit__(*args)
        finally:
            self.close()


class Store:
    def __init__(self, root: Path):
        self.root = root.resolve()
        self.content_lock = threading.RLock()
        self.library_limit = int(
            os.environ.get("ROBOT_LIBRARY_MAX_BYTES", 10 * 1024**3)
        )
        self.disk_reserve = 256 * 1024**2
        if self.library_limit <= 0:
            raise ValueError("ROBOT_LIBRARY_MAX_BYTES必须大于0")
        root.mkdir(parents=True, exist_ok=True, mode=0o700)
        root.chmod(0o700)
        for name in ("assets", "audio", "backups", "models"):
            (root / name).mkdir(exist_ok=True, mode=0o700)
        self.path = root / "library.sqlite3"
        with self.connect() as db:
            db.executescript(SCHEMA)
            db.execute("INSERT OR IGNORE INTO meta VALUES('schema','1')")
            db.execute("INSERT OR IGNORE INTO meta VALUES('service_id',?)", (uid(),))
            db.execute("INSERT OR IGNORE INTO meta VALUES('catalog','0')")
            version = db.execute(
                "SELECT value FROM meta WHERE key='schema'"
            ).fetchone()[0]
            if version not in ("1", "2"):
                raise RuntimeError("不兼容的数据库版本，拒绝启动")
            if version == "1":
                columns = {row["name"] for row in db.execute("PRAGMA table_info(jobs)")}
                if "options" not in columns:
                    db.execute(
                        "ALTER TABLE jobs ADD COLUMN options TEXT NOT NULL DEFAULT '{}'"
                    )
                db.execute("UPDATE meta SET value='2' WHERE key='schema'")
            db.commit()
        self.path.chmod(0o600)

    def ensure_capacity(self, additional):
        used = 0
        for folder in ("assets", "audio"):
            for path in (self.root / folder).rglob("*"):
                try:
                    if path.is_file():
                        used += path.stat().st_size
                except FileNotFoundError:
                    continue
        if used + max(0, additional) > self.library_limit:
            raise StorageCapacityError("资源库容量已满，请清理资源或调整电脑容量上限")
        if shutil.disk_usage(self.root).free < max(0, additional) + self.disk_reserve:
            raise StorageCapacityError("磁盘空间不足，已停止写入并保留已有内容")

    def write_content(self, path: Path, data: bytes):
        with self.content_lock:
            if not any(
                path.resolve().is_relative_to(self.root / folder)
                for folder in ("assets", "audio")
            ):
                raise ValueError("非内容目录")
            old_size = path.stat().st_size if path.is_file() else 0
            self.ensure_capacity(len(data) - old_size)
            # 原子替换需要额外临时空间，不能只按净增加量检查磁盘。
            if shutil.disk_usage(self.root).free < len(data) + self.disk_reserve:
                raise StorageCapacityError("磁盘临时空间不足")
            path.parent.mkdir(parents=True, exist_ok=True)
            temp = path.with_name(path.name + "." + uid() + ".tmp")
            try:
                temp.write_bytes(data)
                temp.replace(path)
            finally:
                temp.unlink(missing_ok=True)

    @contextmanager
    def new_content_batch(self):
        with self.content_lock:
            created = []
            try:
                yield created
            except BaseException:
                for path in created:
                    path.unlink(missing_ok=True)
                raise

    def connect(self):
        db = sqlite3.connect(self.path, timeout=10, factory=ClosingConnection)
        db.row_factory = sqlite3.Row
        db.execute("PRAGMA foreign_keys=ON")
        db.execute("PRAGMA journal_mode=WAL")
        return db

    @contextmanager
    def transaction(self):
        db = self.connect()
        try:
            db.execute("BEGIN IMMEDIATE")
            yield db
            db.commit()
        except BaseException:
            db.rollback()
            raise
        finally:
            db.close()

    def read(self, query, params=()):
        with self.connect() as db:
            return [dict(r) for r in db.execute(query, params)]

    def one(self, query, params=()):
        rows = self.read(query, params)
        return rows[0] if rows else None

    def meta(self, key):
        return self.one("SELECT value FROM meta WHERE key=?", (key,))["value"]

    def issue_invite(self, kind, robot_id=None, ttl=120):
        token = secrets.token_urlsafe(32)
        with self.transaction() as db:
            db.execute(
                "INSERT INTO invites VALUES(?,?,?,?,0)",
                (digest(token), kind, robot_id, time.time() + ttl),
            )
        return token


def bump_catalog(db):
    db.execute("UPDATE meta SET value=CAST(value AS INTEGER)+1 WHERE key='catalog'")
