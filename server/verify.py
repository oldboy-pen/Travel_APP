"""端到端验证脚本：注册 → 重复注册 → 登录 → 拉取用户列表

用法（服务器已启动后）：
    python verify.py [服务器地址]        # 默认 http://127.0.0.1:8000

注意：本机有系统代理，这里用 ProxyHandler({}) 强制直连，否则会打到代理上去。
"""

import json
import sys
import urllib.error
import urllib.request

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8000"
# 绕过系统代理，直连本机服务器
opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def call(method: str, path: str, body: dict | None = None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(
        BASE + path,
        data=data,
        method=method,
        headers={"Content-Type": "application/json", "Accept": "application/json"},
    )
    try:
        with opener.open(req, timeout=10) as resp:
            return resp.status, json.loads(resp.read().decode())
    except urllib.error.HTTPError as e:
        raw = e.read().decode()
        try:
            return e.code, json.loads(raw)
        except json.JSONDecodeError:
            return e.code, {"raw": raw}


def main() -> None:
    print(f"服务器：{BASE}\n")

    code, res = call("GET", "/health")
    print(f"[1] 健康检查           -> {code} {res}")
    if code != 200:
        print("服务器没起来，先检查是否在运行")
        return

    user = "verify_user"
    pwd = "pwd123456"

    code, res = call("POST", "/api/register", {"username": user, "password": pwd, "nickname": "验证账号"})
    print(f"[2] 注册 {user}        -> {code} {res}")

    code, res = call("POST", "/api/register", {"username": user, "password": pwd})
    print(f"[3] 重复注册（应 409） -> {code} {res}")

    code, res = call("POST", "/api/login", {"username": user, "password": pwd})
    print(f"[4] 正确密码登录       -> {code} {res}")

    code, res = call("POST", "/api/login", {"username": user, "password": "wrongpwd"})
    print(f"[5] 错误密码（应 401） -> {code} {res}")

    code, res = call("GET", "/api/users")
    print(f"[6] 用户列表           -> {code} {res}")

    users = res.get("users", []) if code == 200 else []
    ok = any(u["username"].lower() == user for u in users)
    print(f"\n结论：注册用户出现在服务器列表中 = {ok}（True 表示数据确实落库）")


if __name__ == "__main__":
    main()
