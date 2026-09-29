"""命令行查看服务器上已同步的轨迹（不启动服务器也能用）

用法：
    python dump_tracks.py                 # 列出全部轨迹
    python dump_tracks.py <track_id>      # 看某条轨迹的完整信息（点位采样）
    python dump_tracks.py --json          # 输出原始 JSON（可重定向到文件）

和浏览器打开 /view/tracks 看到的是同一份数据，只是这里不用开服务器。
"""

import json
import os
import sqlite3
import sys
import time

DB_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "users.db")


def pad(s: str, width: int) -> str:
    """按显示宽度补齐：中文占 2 列，直接 ljust 会错位"""
    w = sum(2 if ord(c) > 127 else 1 for c in str(s))
    return str(s) + " " * max(0, width - w)


def fmt_km(m) -> str:
    return f"{float(m or 0) / 1000:.2f}"


def fmt_dur(ms) -> str:
    ms = int(ms or 0)
    if not ms:
        return "-"
    s, h = ms // 1000, 0
    h, m = divmod(s // 60, 60)
    return f"{h}h{m:02d}m" if h else f"{m}m{s % 60:02d}s"


def main() -> None:
    if not os.path.exists(DB_PATH):
        print(f"还没有数据库：{DB_PATH}\n先启动服务器并同步一次轨迹。")
        return

    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row
    rows = conn.execute(
        """SELECT t.user_id, t.id, t.data, t.updated_at, u.username, u.nickname
           FROM tracks t LEFT JOIN users u ON u.id = t.user_id
           ORDER BY t.updated_at DESC"""
    ).fetchall()
    conn.close()

    if not rows:
        print("服务器上还没有轨迹。在 App「我的 → 同步到云端」上传后再来看。")
        return

    # ---- 单条详情 ----
    arg = sys.argv[1] if len(sys.argv) > 1 else None
    if arg and arg != "--json":
        hit = [r for r in rows if r["id"] == arg or r["id"].endswith(arg)]
        if not hit:
            print(f"没找到轨迹 {arg}\n可用 id：{', '.join(r['id'] for r in rows)}")
            return
        r = hit[0]
        d = json.loads(r["data"])
        pts = d.get("points") or []
        wps = d.get("waypoints") or []
        print(f"轨迹      {d.get('name')}")
        print(f"id        {r['id']}")
        print(f"所属账号  {r['nickname']} @{r['username']}  (user_id={r['user_id']})")
        print(f"时间      {fmt_time(d.get('startTime'))} → {fmt_time(d.get('endTime'))}")
        print(f"里程      {fmt_km(d.get('distance'))} km")
        print(f"用时      {fmt_dur(d.get('duration'))}")
        print(f"爬升      {float(d.get('climb') or 0):.1f} m")
        print(f"点位      {len(pts)} 个轨迹点 / {len(wps)} 个打卡点")
        print(f"类型      {d.get('activityType')}")
        print(f"同步时间  {fmt_time(r['updated_at'])}")
        if pts:
            p0, p1 = pts[0], pts[-1]
            print(f"起点      {p0.get('lat'):.6f}, {p0.get('lng'):.6f}")
            print(f"终点      {p1.get('lat'):.6f}, {p1.get('lng'):.6f}")
        for w in wps[:10]:
            print(f"打卡点    {w.get('name')}  {w.get('lat'):.6f}, {w.get('lng'):.6f}"
                  + (f"  {w.get('text')}" if w.get("text") else ""))
        if len(wps) > 10:
            print(f"          ...还有 {len(wps) - 10} 个打卡点")
        print("\n完整 JSON：")
        print(json.dumps(d, ensure_ascii=False, indent=2)[:2000]
              + ("\n...（截断，用 --json 导出完整内容）" if len(json.dumps(d)) > 2000 else ""))
        return

    # ---- 原始 JSON 导出 ----
    if arg == "--json":
        out = [json.loads(r["data"]) for r in rows]
        print(json.dumps(out, ensure_ascii=False, indent=2))
        return

    # ---- 列表 ----
    print(f"{pad('轨迹名', 22)}{pad('账号', 18)}{pad('开始时间', 18)}"
          f"{pad('里程km', 9)}{pad('用时', 10)}{pad('爬升m', 8)}点位   同步时间")
    print("-" * 104)
    total = 0.0
    for r in rows:
        d = json.loads(r["data"])
        total += float(d.get("distance") or 0)
        # 部分导入轨迹的名字里带换行，直接打印会把表格撑开
        name = " ".join(str(d.get("name") or "(未命名)").split())
        print(
            pad(name, 22)
            + pad(f"{r['nickname'] or '?'}@{r['username'] or '?'}", 18)
            + pad(fmt_time(d.get("startTime")), 18)
            + pad(fmt_km(d.get("distance")), 9)
            + pad(fmt_dur(d.get("duration")), 10)
            + pad(f"{float(d.get('climb') or 0):.0f}", 8)
            + pad(f"{len(d.get('points') or [])}点/{len(d.get('waypoints') or [])}打卡", 10)
            + fmt_time(r["updated_at"])
        )
    print("-" * 104)
    print(f"共 {len(rows)} 条轨迹，总里程 {total / 1000:.2f} km")
    print("\n看某条详情：python dump_tracks.py <track_id>")
    print("浏览器查看：启动服务器后打开 http://127.0.0.1:8000/view/tracks")


def fmt_time(ms) -> str:
    if not ms:
        return "-"
    return time.strftime("%Y-%m-%d %H:%M", time.localtime(int(ms) / 1000))


if __name__ == "__main__":
    main()
