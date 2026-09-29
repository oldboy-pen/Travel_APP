"""Travel APP 临时验证服务器：用户注册 → 数据落库（SQLite）

只用于在本机验证「云端注册保存用户数据」「本地轨迹同步到服务器」这两条链路，
跑完即弃，不做鉴权加固。

启动（在 server/ 目录下）：
    python app.py
等价写法：
    python -m uvicorn app:app --host 0.0.0.0 --port 8000

接口：
    GET  /health                         健康检查
    POST /api/register    {"username","password","nickname"?} → 注册并返回用户
    POST /api/login       {"username","password"}             → 登录并返回用户
    GET  /api/users       已注册用户列表（不含密码哈希，验证落库用）

    POST /api/tracks      {"user_id","tracks":[ 轨迹JSON... ]} → 批量 upsert，返回 {"synced":N}
    GET  /api/tracks?user_id=    某用户的轨迹摘要列表（验证"同步成功落库"用）
    GET  /api/tracks/{user_id}/{track_id}  单条轨迹完整 JSON

  「在云端看轨迹」专用（不用记 user_id，浏览器直接开）：
    GET  /view/tracks            网页版轨迹总览（按用户分组 + 里程/爬升剖面图）
    GET  /api/tracks/all         总览数据 JSON（网页页用的就是它）
    GET  /docs            FastAPI 自带调试页
"""

import hashlib
import hmac
import html
import json
import math
import os
import secrets
import socket
import sqlite3
import time
from contextlib import contextmanager
from typing import Optional

from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import HTMLResponse
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
        # 轨迹表：每张卡 (user_id, id) 唯一，便于同一用户重复同步时 upsert 覆盖。
        # data 存轨迹完整 JSON（与 Android 端 TrackRepository 的序列化结构一致），
        # 同时冗余几个摘要字段给列表接口用，避免每次拉列表都解析整张 JSON。
        c.execute(
            """CREATE TABLE IF NOT EXISTS tracks (
                user_id    TEXT NOT NULL,
                id         TEXT NOT NULL,
                data       TEXT NOT NULL,
                updated_at INTEGER NOT NULL,
                PRIMARY KEY (user_id, id)
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


class TrackUploadReq(BaseModel):
    """批量上传轨迹：user_id 关联云端账号；tracks 是轨迹完整 JSON 列表"""
    user_id: str
    tracks: list[dict]


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


# ---------- 轨迹同步 ----------

@app.post("/api/tracks")
def upload_tracks(req: TrackUploadReq):
    """批量 upsert 轨迹：按 (user_id, id) 覆盖写入，返回成功条数。

    轨迹 JSON 结构与 Android 端 TrackRepository 序列化的字段一致：
    id / name / startTime / endTime / distance / duration / climb /
    activityType / points[] / waypoints[]。
    """
    now = int(time.time() * 1000)
    synced = 0
    with db() as c:
        for t in req.tracks:
            tid = t.get("id")
            if not tid:          # 缺 id 的轨迹无法定位，跳过而不是报错
                continue
            c.execute(
                """INSERT INTO tracks (user_id, id, data, updated_at)
                   VALUES (:user_id, :id, :data, :updated_at)
                   ON CONFLICT(user_id, id) DO UPDATE SET
                       data=excluded.data, updated_at=excluded.updated_at""",
                {
                    "user_id": req.user_id,
                    "id": str(tid),
                    "data": json.dumps(t, ensure_ascii=False),
                    "updated_at": now,
                },
            )
            synced += 1
    return {"synced": synced}


@app.get("/api/tracks")
def list_tracks(user_id: str, limit: int = 200):
    """某用户的轨迹摘要列表（不返回点位，验证同步落库用）"""
    with db() as c:
        rows = c.execute(
            "SELECT id, data, updated_at FROM tracks WHERE user_id = ? "
            "ORDER BY updated_at DESC LIMIT ?",
            (user_id, min(limit, 500)),
        ).fetchall()
    tracks = []
    for r in rows:
        d = json.loads(r["data"])
        tracks.append({
            "id": r["id"],
            "name": d.get("name"),
            "distance": d.get("distance"),
            "startTime": d.get("startTime"),
            "endTime": d.get("endTime"),
            "pointCount": len(d.get("points") or []),
            "waypointCount": len(d.get("waypoints") or []),
            "activityType": d.get("activityType"),
            "updated_at": r["updated_at"],
        })
    return {"count": len(tracks), "tracks": tracks}


@app.get("/api/tracks/{user_id}/{track_id}")
def get_track(user_id: str, track_id: str):
    """单条轨迹完整 JSON（含 points / waypoints），供下载/回放"""
    with db() as c:
        row = c.execute(
            "SELECT data FROM tracks WHERE user_id = ? AND id = ?",
            (user_id, track_id),
        ).fetchone()
    if row is None:
        raise HTTPException(status_code=404, detail="轨迹不存在")
    return {"track": json.loads(row["data"])}


# ---------- 轨迹查看（云端查看页的数据源） ----------

EARTH_R = 6_371_008.8   # 地球平均半径（米）


def haversine(lat1: float, lng1: float, lat2: float, lng2: float) -> float:
    """两点球面距离（米），用于给剖面图算累计里程"""
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp = p2 - p1
    dl = math.radians(lng2 - lng1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * EARTH_R * math.asin(math.sqrt(a))


def sample_profile(points: list, max_points: int = 120) -> dict:
    """把点位抽稀成「累计里程 / 海拔」两条等长序列，供前端画剖面图

    直接把几千个点塞进页面会让 JSON 很大，等距抽样到 120 个点后
    形状几乎不变，体积能压掉一个数量级。
    """
    pts = [p for p in (points or []) if isinstance(p, dict)]
    if len(pts) < 2:
        return {"dist": [], "alt": []}

    step = max(1, len(pts) // max_points)
    sel = pts[::step]
    if sel[-1] is not pts[-1]:
        sel.append(pts[-1])

    dists, alts, acc, prev = [], [], 0.0, None
    for p in sel:
        lat, lng = p.get("lat"), p.get("lng")
        if lat is None or lng is None:
            continue
        if prev is not None:
            acc += haversine(prev[0], prev[1], lat, lng)
        prev = (lat, lng)
        dists.append(round(acc, 1))
        alts.append(round(float(p.get("alt") or 0.0), 1))
    return {"dist": dists, "alt": alts}


def summarize(row: sqlite3.Row, username: str, nickname: str) -> dict:
    """一条轨迹 → 查看页需要的摘要（含抽样剖面，不含全部点位）"""
    d = json.loads(row["data"])
    points = d.get("points") or []
    return {
        "user_id": row["user_id"],
        "username": username,
        "nickname": nickname,
        "id": row["id"],
        "name": d.get("name") or "(未命名)",
        "distance": round(float(d.get("distance") or 0.0), 1),
        "duration": int(d.get("duration") or 0),
        "climb": round(float(d.get("climb") or 0.0), 1),
        "startTime": int(d.get("startTime") or 0),
        "endTime": int(d.get("endTime") or 0),
        "pointCount": len(points),
        "waypointCount": len(d.get("waypoints") or []),
        "activityType": d.get("activityType"),
        "updated_at": row["updated_at"],
        "profile": sample_profile(points),
        "firstPoint": (points[0] if points and isinstance(points[0], dict) else {}),
    }


@app.get("/api/tracks/all")
def list_all_tracks(limit: int = 500):
    """跨用户轨迹总览：不用记 user_id，直接列出服务器上所有轨迹

    返回按更新时间倒序的摘要列表 + 用户分组统计。每条带抽样后的
    里程/海拔序列（profile），前端据此画剖面图。
    """
    limit = max(1, min(limit, 2000))
    with db() as c:
        rows = c.execute(
            """SELECT t.user_id, t.id, t.data, t.updated_at,
                      u.username, u.nickname
               FROM tracks t LEFT JOIN users u ON u.id = t.user_id
               ORDER BY t.updated_at DESC LIMIT ?""",
            (limit,),
        ).fetchall()
        users = c.execute(
            """SELECT u.id, u.username, u.nickname, COUNT(t.id) AS track_count
               FROM users u LEFT JOIN tracks t ON t.user_id = u.id
               GROUP BY u.id ORDER BY u.created_at DESC"""
        ).fetchall()

    tracks = [
        summarize(r, r["username"] or "(已删除用户)", r["nickname"] or "") for r in rows
    ]
    return {
        "count": len(tracks),
        "totalDistance": round(sum(t["distance"] for t in tracks), 1),
        "users": [
            {"id": u["id"], "username": u["username"],
             "nickname": u["nickname"], "trackCount": u["track_count"]}
            for u in users
        ],
        "tracks": tracks,
    }


@app.get("/view/tracks", response_class=HTMLResponse)
def view_tracks():
    """网页版轨迹总览：浏览器打开就能看，数据由 /api/tracks/all 提供

    页面本身不含数据，打开后前端再拉一次接口，所以点浏览器刷新即为最新。
    """
    return TRACKS_PAGE_HTML


TRACKS_PAGE_HTML = """<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>云端轨迹总览 · Travel APP</title>
<style>
  :root {
    --bg: #15181d; --panel: #1d2129; --panel2: #232833; --line: #2e3440;
    --txt: #e6e9ef; --dim: #939aa8; --accent: #4ea1ff; --ok: #38c793; --warn: #f5a623;
  }
  * { box-sizing: border-box; }
  body { margin: 0; background: var(--bg); color: var(--txt);
         font: 14px/1.6 -apple-system, "Segoe UI", "Microsoft YaHei", sans-serif; }
  header { padding: 20px 24px 12px; border-bottom: 1px solid var(--line); }
  h1 { margin: 0 0 4px; font-size: 19px; }
  .sub { color: var(--dim); font-size: 13px; }
  .bar { display: flex; flex-wrap: wrap; gap: 12px; align-items: center;
         padding: 14px 24px; border-bottom: 1px solid var(--line); }
  .stat { background: var(--panel); border: 1px solid var(--line); border-radius: 8px;
          padding: 6px 14px; }
  .stat b { font-size: 17px; color: var(--accent); }
  .stat span { color: var(--dim); font-size: 12px; margin-left: 4px; }
  select, button { background: var(--panel2); color: var(--txt);
                   border: 1px solid var(--line); border-radius: 8px;
                   padding: 7px 12px; font-size: 13px; cursor: pointer; }
  button:hover, select:hover { border-color: var(--accent); }
  main { padding: 16px 24px 60px; }
  .group { margin-bottom: 22px; }
  .group h2 { font-size: 15px; margin: 0 0 10px; color: var(--dim); font-weight: 600; }
  table { width: 100%; border-collapse: collapse; background: var(--panel);
          border: 1px solid var(--line); border-radius: 10px; overflow: hidden; }
  th { text-align: left; font-weight: 600; color: var(--dim); font-size: 12px;
       padding: 10px 12px; background: var(--panel2); }
  td { padding: 10px 12px; border-top: 1px solid var(--line); font-size: 13px; }
  tr.row { cursor: pointer; }
  tr.row:hover { background: var(--panel2); }
  .name { color: var(--txt); font-weight: 600; }
  .meta { color: var(--dim); font-size: 12px; }
  .tag { display: inline-block; padding: 1px 8px; border-radius: 20px; font-size: 11px;
         background: #2b3242; color: var(--dim); margin-left: 6px; }
  .tag.synced { background: rgba(56,199,147,.14); color: var(--ok); }
  tr.detail td { background: #191d24; padding: 0; }
  .detailbox { padding: 14px 16px; display: flex; flex-wrap: wrap; gap: 18px; }
  .kv { display: grid; grid-template-columns: auto auto; gap: 2px 14px; font-size: 12px; }
  .kv dt { color: var(--dim); }
  .kv dd { margin: 0; }
  svg { background: #12151a; border: 1px solid var(--line); border-radius: 8px; }
  .empty { color: var(--dim); padding: 40px; text-align: center; }
  a { color: var(--accent); }
</style>
</head>
<body>
<header>
  <h1>云端轨迹总览</h1>
  <div class="sub">数据来自本机 SQLite（server/users.db）。刷新页面即为最新；App 里点「同步到云端」后回到这里刷新即可看到。</div>
</header>

<div class="bar">
  <div class="stat"><b id="s-tracks">-</b><span>条轨迹</span></div>
  <div class="stat"><b id="s-users">-</b><span>个账号</span></div>
  <div class="stat"><b id="s-dist">-</b><span>总里程 km</span></div>
  <select id="filter"><option value="">全部账号</option></select>
  <button id="reload">刷新</button>
  <span class="meta" id="status"></span>
</div>

<main id="main"><div class="empty">加载中…</div></main>

<script>
const fmtKm = m => (m / 1000).toFixed(2);
const fmtDur = ms => {
  if (!ms) return "-";
  const s = Math.round(ms / 1000), h = Math.floor(s / 3600), m = Math.floor(s % 3600 / 60);
  return (h ? h + "h" : "") + (h || m ? m + "m" : "") + (s % 60) + "s";
};
const fmtTime = ms => ms ? new Date(ms).toLocaleString("zh-CN", { hour12: false }) : "-";
const esc = s => String(s ?? "").replace(/[&<>"]/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]));

/** 里程-海拔剖面图：横轴累计里程、纵轴海拔，纯数据统计图（不含底图） */
function profileSvg(profile) {
  const d = (profile && profile.dist) || [], a = (profile && profile.alt) || [];
  if (d.length < 2) return '<div class="meta">点位不足，无剖面图</div>';
  const W = 520, H = 150, P = 26;
  const maxD = Math.max(...d) || 1, minA = Math.min(...a), maxA = Math.max(...a);
  const span = (maxA - minA) || 1;
  const x = v => P + v / maxD * (W - 2 * P);
  const y = v => H - P - (v - minA) / span * (H - 2 * P);
  const line = d.map((v, i) => (i ? "L" : "M") + x(v).toFixed(1) + " " + y(a[i]).toFixed(1)).join(" ");
  const area = line + " L" + x(maxD).toFixed(1) + " " + (H - P) + " L" + x(0).toFixed(1) + " " + (H - P) + " Z";
  let g = "";
  for (let i = 0; i <= 4; i++) {
    const yy = P + i / 4 * (H - 2 * P);
    g += '<line x1="' + P + '" y1="' + yy + '" x2="' + (W - P) + '" y2="' + yy + '" stroke="#2e3440"/>';
  }
  return '<svg width="' + W + '" height="' + H + '" viewBox="0 0 ' + W + ' ' + H + '">' +
    g + '<path d="' + area + '" fill="rgba(78,161,255,.16)"/>' +
    '<path d="' + line + '" fill="none" stroke="#4ea1ff" stroke-width="1.6"/>' +
    '<text x="' + P + '" y="14" fill="#939aa8" font-size="11">海拔 ' + maxA.toFixed(0) + 'm</text>' +
    '<text x="' + P + '" y="' + (H - 8) + '" fill="#939aa8" font-size="11">最低 ' + minA.toFixed(0) +
    'm · 全程 ' + fmtKm(maxD) + ' km</text></svg>';
}

function detailHtml(t) {
  const p = t.firstPoint || {};
  const lat = p.lat, lng = p.lng;
  return '<div class="detailbox"><div>' + profileSvg(t.profile) + '</div><div>' +
    '<dl class="kv">' +
    "<dt>轨迹 id</dt><dd>" + esc(t.id) + "</dd>" +
    "<dt>所属账号</dt><dd>" + esc(t.nickname) + " @" + esc(t.username) + "</dd>" +
    "<dt>开始 / 结束</dt><dd>" + fmtTime(t.startTime) + " → " + fmtTime(t.endTime) + "</dd>" +
    "<dt>里程</dt><dd>" + fmtKm(t.distance) + " km</dd>" +
    "<dt>用时</dt><dd>" + fmtDur(t.duration) + "</dd>" +
    "<dt>累计爬升</dt><dd>" + t.climb.toFixed(1) + " m</dd>" +
    "<dt>轨迹点 / 打卡点</dt><dd>" + t.pointCount + " / " + t.waypointCount + "</dd>" +
    "<dt>起点坐标</dt><dd>" + (lat != null ? lat.toFixed(6) + ", " + lng.toFixed(6) : "-") + "</dd>" +
    "<dt>上传时间</dt><dd>" + fmtTime(t.updated_at) + "</dd>" +
    "</dl><div style='margin-top:10px'><a href='/api/tracks/" + encodeURIComponent(t.user_id) +
    "/" + encodeURIComponent(t.id) + "' target='_blank'>查看完整 JSON（含全部点位）</a></div></div></div>";
}

function render(data) {
  document.getElementById("s-tracks").textContent = data.count;
  document.getElementById("s-users").textContent = data.users.length;
  document.getElementById("s-dist").textContent = (data.totalDistance / 1000).toFixed(1);

  const sel = document.getElementById("filter");
  const cur = sel.value;
  sel.innerHTML = '<option value="">全部账号</option>' + data.users.map(u =>
    '<option value="' + esc(u.id) + '">' + esc(u.nickname) + " @" + esc(u.username) + "（" + u.trackCount + "）</option>"
  ).join("");
  sel.value = cur;

  const tracks = data.tracks.filter(t => !cur || t.user_id === cur);
  const main = document.getElementById("main");
  if (!tracks.length) {
    main.innerHTML = '<div class="empty">还没有轨迹。在 App「我的 → 同步到云端」上传后回来刷新。</div>';
    return;
  }
  // 按账号分组
  const groups = {};
  tracks.forEach(t => (groups[t.user_id] = groups[t.user_id] || []).push(t));
  main.innerHTML = Object.keys(groups).map(uid => {
    const g = groups[uid];
    return '<div class="group"><h2>' + esc(g[0].nickname) + " @" + esc(g[0].username) +
      " · " + g.length + " 条</h2><table><thead><tr>" +
      "<th>轨迹</th><th>日期</th><th>里程</th><th>用时</th><th>爬升</th><th>点位</th></tr></thead><tbody>" +
      g.map((t, i) =>
        '<tr class="row" data-i="' + uid + "|" + i + '"><td><span class="name">' + esc(t.name) + "</span>" +
        (t.activityType ? '<span class="tag">' + esc(t.activityType) + "</span>" : "") +
        '<div class="meta">' + esc(t.id) + "</div></td>" +
        "<td>" + fmtTime(t.startTime) + "</td>" +
        "<td>" + fmtKm(t.distance) + " km</td>" +
        "<td>" + fmtDur(t.duration) + "</td>" +
        "<td>" + t.climb.toFixed(0) + " m</td>" +
        "<td>" + t.pointCount + " 点 / " + t.waypointCount + " 打卡</td></tr>" +
        '<tr class="detail" data-d="' + uid + "|" + i + '" style="display:none"><td colspan="6">' +
        detailHtml(t) + "</td></tr>"
      ).join("") + "</tbody></table></div>";
  }).join("");

  // 点行展开/收起详情
  main.querySelectorAll("tr.row").forEach(tr => {
    tr.onclick = () => {
      const d = main.querySelector('tr.detail[data-d="' + tr.dataset.i + '"]');
      if (d) d.style.display = d.style.display === "none" ? "" : "none";
    };
  });
}

async function load() {
  const st = document.getElementById("status");
  st.textContent = "加载中…";
  try {
    const r = await fetch("/api/tracks/all");
    if (!r.ok) throw new Error("HTTP " + r.status);
    render(await r.json());
    st.textContent = "更新于 " + new Date().toLocaleTimeString("zh-CN", { hour12: false });
  } catch (e) {
    st.textContent = "加载失败：" + e.message;
  }
}
document.getElementById("reload").onclick = load;
document.getElementById("filter").onchange = load;
load();
setInterval(load, 30000);
</script>
</body>
</html>
"""


def lan_ip() -> str:
    """取"出网网卡"的局域网 IP：真机要填的就是它

    不发包，只是让系统查一次路由表，所以不会真的连到 8.8.8.8。
    机器上一般有多个虚拟网卡（WSL/VMware），直接从 ipconfig 里挑容易挑错。
    """
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        sock.connect(("8.8.8.8", 80))
        return sock.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        sock.close()


if __name__ == "__main__":
    import uvicorn

    port = 8000
    print("=" * 62)
    print("  Travel APP 临时验证服务器")
    print(f"  轨迹总览(本机浏览器)  http://127.0.0.1:{port}/view/tracks")
    print(f"  接口文档(本机浏览器)  http://127.0.0.1:{port}/docs")
    print(f"  用户列表(本机浏览器)  http://127.0.0.1:{port}/api/users")
    print(f"  真机 App 里填        http://{lan_ip()}:{port}")
    print( "  模拟器 App 里填      http://10.0.2.2:8000")
    print("  停止服务              Ctrl+C")
    print("=" * 62)

    # 0.0.0.0：让同一局域网内的手机真机能访问
    uvicorn.run(app, host="0.0.0.0", port=port, log_level="info")
