"""直接打开 SQLite 查看已注册用户（服务器没启动也能用）

用法：
    python dump_users.py                 # 列出所有用户
    python dump_users.py <用户名> <密码>  # 校验这个密码对不对（不显示明文）

列出时输出 id / 用户名 / 昵称 / 注册时间 / 口令哈希前 12 位 / 盐前 8 位，
用来确认"用户数据确实写进了数据库"。
"""

import hashlib
import hmac
import os
import sqlite3
import sys
import time

DB_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "users.db")
# 必须与 app.py 的 ITERATIONS 保持一致，否则校验永远不通过
ITERATIONS = 120_000


def disp_width(text: str) -> int:
    """显示宽度：中文等宽字符按 2 列算，保证终端里表格对齐"""
    return sum(2 if ord(ch) > 127 else 1 for ch in text)


def pad(text: str, width: int) -> str:
    return text + " " * max(0, width - disp_width(text))


def check_password(username: str, password: str) -> None:
    """校验密码：用库里的盐重算一遍哈希做比对——服务端只做"比对"，拿不到明文"""
    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row
    row = conn.execute(
        "SELECT username, salt, password_hash FROM users WHERE username = ?", (username,)
    ).fetchone()
    conn.close()

    if row is None:
        print(f"用户不存在：{username}")
        return

    calc = hashlib.pbkdf2_hmac(
        "sha256", password.encode("utf-8"), bytes.fromhex(row["salt"]), ITERATIONS
    ).hex()
    ok = hmac.compare_digest(calc, row["password_hash"])

    print(f"用户：{row['username']}")
    print(f"库中哈希：{row['password_hash'][:24]}…")
    print(f"本次算得：{calc[:24]}…")
    print(f"结果：密码{'正确' if ok else '错误'}")
    print("\n注意：这里是拿你输入的密码重算一遍再比对，")
    print("      数据库里只有哈希和盐，没有任何一处存着明文，也反推不出来。")


def main() -> None:
    if not os.path.exists(DB_PATH):
        print(f"还没有数据库：{DB_PATH}\n先启动服务器并注册一个用户。")
        return

    # 带两个参数 = 校验密码模式
    if len(sys.argv) == 3:
        check_password(sys.argv[1], sys.argv[2])
        return
    if len(sys.argv) > 1:
        print("用法：python dump_users.py [用户名 密码]")
        return

    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row
    rows = conn.execute("SELECT * FROM users ORDER BY created_at DESC").fetchall()
    conn.close()

    if not rows:
        print("数据库存在但 users 表为空。")
        return

    print(f"数据库：{DB_PATH}")
    print(f"共 {len(rows)} 个用户\n")
    print(pad("id", 26) + pad("用户名", 14) + pad("昵称", 14) + pad("注册时间", 21)
          + pad("口令哈希(前12)", 16) + "盐(前8)")
    print("-" * 100)
    for r in rows:
        ts = time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(r["created_at"] / 1000))
        print(pad(r["id"], 26) + pad(r["username"], 14) + pad(r["nickname"], 14) + pad(ts, 21)
              + pad(r["password_hash"][:12], 16) + r["salt"][:8])
    print("\n说明：口令只存哈希+盐，没有明文；同一密码两次注册得到的哈希不同（盐不同）。")


if __name__ == "__main__":
    sys.exit(main())
