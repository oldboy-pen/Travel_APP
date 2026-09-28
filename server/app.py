"""Travel APP 临时验证服务器：用户注册 → 数据落库（SQLite）

只用于在本机验证「云端注册保存用户数据」这条链路，跑完即弃，不做鉴权加固。

启动（在 server/ 目录下）：
    python app.py
等价写法：
    python -m uvicorn app:app --host 0.0.0.0 --port 8000

接口：
    GET  /health          健康检查
    POST /api/register    {"username","password","nickname"?} → 注册并返回用户
    POST /api/login       {"username","password"}             → 登录并返回用户
    GET  /api/users       已注册用户列表（不含密码哈希，验证落库用）
    GET  /docs            FastAPI 自带调试页
"""

import hashlib
import hmac
import os
import secrets
import sqlite3
import time
from contextlib import contextmanager
from typing import Optional

from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel, Field

DB_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "users.db")
ITERATIONS = 120_000          # 与 Android 端 PasswordHasher 保持一致
USERNAME_MIN, USERNAME_MAX = 3, 20
PASSWORD_MIN, PASSWORD_MAX = 6, 32

app = FastAPI(title="Travel APP 临时账号服务")
# 允许任意来源，方便浏览器 /docs 和真机调试
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)


# ---------- 数据库 ----------

@contextmanager
def db():
    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row
    try:
        yield conn
        conn.commit()
    finally:
        conn.close()


def init_db() -> None:
    with db() as c:
        # COLLATE NOCASE：用户名唯一性忽略大小写，和 Android 端判定规则一致
        c.execute(
            """CREATE TABLE IF NOT EXISTS users (
                id            TEXT PRIMARY KEY,
                username      TEXT NOT NULL UNIQUE COLLATE NOCASE,
                nickname      TEXT NOT NULL,
                password_hash TEXT NOT NULL,
                salt          TEXT NOT NULL,
                created_at    INTEGER NOT NULL
            )"""
        )


init_db()


# ---------- 口令 ----------

def hash_password(password: str, salt: bytes) -> str:
    return hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"), salt, ITERATIONS).hex()


def public_user(row: sqlite3.Row) -> dict:
    """对外返回的用户信息：绝不携带 salt / 哈希"""
    return {
        "id": row["id"],
        "username": row["username"],
        "nickname": row["nickname"],
        "created_at": row["created_at"],
    }


# ---------- 请求体 ----------

class RegisterReq(BaseModel):
    username: str = Field(min_length=USERNAME_MIN, max_length=USERNAME_MAX)
    password: str = Field(min_length=PASSWORD_MIN, max_length=PASSWORD_MAX)
    nickname: Optional[str] = None


class LoginReq(BaseModel):
    username: str
    password: str


# ---------- 接口 ----------

@app.get("/health")
def health():
    return {"ok": True, "time": int(time.time())}


@app.post("/api/register")
def register(req: RegisterReq):
    username = req.username.strip()
    if not (USERNAME_MIN <= len(username) <= USERNAME_MAX):
        raise HTTPException(status_code=400, detail=f"用户名需 {USERNAME_MIN}-{USERNAME_MAX} 个字符")

    salt = secrets.token_bytes(16)
    user = {
        "id": f"u_{int(time.time() * 1000)}_{secrets.token_hex(3)}",
        "username": username,
        "nickname": (req.nickname or "").strip() or username,
        "password_hash": hash_password(req.password, salt),
        "salt": salt.hex(),
        "created_at": int(time.time() * 1000),
    }
    try:
        with db() as c:
            c.execute(
                """INSERT INTO users (id, username, nickname, password_hash, salt, created_at)
                   VALUES (:id, :username, :nickname, :password_hash, :salt, :created_at)""",
                user,
            )
    except sqlite3.IntegrityError:
        raise HTTPException(status_code=409, detail="该用户名已被注册")

    return {"user": {"id": user["id"], "username": user["username"],
                     "nickname": user["nickname"], "created_at": user["created_at"]}}


@app.post("/api/login")
def login(req: LoginReq):
    with db() as c:
        row = c.execute(
            "SELECT * FROM users WHERE username = ?", (req.username.strip(),)
        ).fetchone()
    # 用户不存在与密码错误返回同一句，不暴露账号是否注册过
    if row is None or not hmac.compare_digest(
        hash_password(req.password, bytes.fromhex(row["salt"])), row["password_hash"]
    ):
        raise HTTPException(status_code=401, detail="用户名或密码错误")

    return {"user": public_user(row)}


@app.get("/api/users")
def list_users(limit: int = 50):
    with db() as c:
        rows = c.execute(
            "SELECT * FROM users ORDER BY created_at DESC LIMIT ?", (min(limit, 200),)
        ).fetchall()
    return {"count": len(rows), "users": [public_user(r) for r in rows]}


if __name__ == "__main__":
    import uvicorn

    # 0.0.0.0：让同一局域网内的手机真机能访问
    uvicorn.run(app, host="0.0.0.0", port=8000, log_level="info")
