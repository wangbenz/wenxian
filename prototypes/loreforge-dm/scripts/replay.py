"""本地受控五步回放。只调用独立demo API；访问凭据和完整证据保存在被忽略的.data目录。"""
import json
import uuid
from datetime import datetime
from pathlib import Path
from urllib.request import Request, urlopen

BASE = "http://127.0.0.1:5189"

def call(path, body=None, token=None):
    headers = {"Accept": "application/json"}
    if token: headers["X-Demo-Token"] = token
    if body is not None: headers["Content-Type"] = "application/json"
    request = Request(BASE + path, data=None if body is None else json.dumps(body, ensure_ascii=False).encode(), headers=headers)
    with urlopen(request, timeout=350) as response:
        return json.load(response)

def main():
    folder = Path(".data/evidence") / datetime.now().strftime("%Y%m%d-%H%M%S")
    folder.mkdir(parents=True, exist_ok=True)
    Path(".data/evidence/latest-run.txt").write_text(str(folder))
    created = call("/api/sessions", {"scenario": "legacy"})
    identifier, token, save = created["id"], created["token"], created["save"]
    (folder / "replay-access.json").write_text(json.dumps({"id": identifier, "token": token}))
    (folder / "replay-access.json").chmod(0o600)
    steps = [
        ("ASK", "我能直接用控物术隔着栅门拔起药畦里的七星草吗？只问可行性，暂不行动。"),
        ("ACT", "我把纸坊便条给程叔看，说明只取一株成熟纸料，询问是否可以帮他把落地木塞放回水槽，换取采收许可。先谈条件，不动草，也不默认他已经答应。"),
        ("ACT", "我站在门边，对三步内落地的木塞施放控物术，缓慢把它放回程叔指示的水槽缺口；只做这件事，完成后收功，暂不采草。"),
        ("ACT", "如果程叔已经允许采收，我走到成熟七星草旁，用小剪只剪一株的纸料，留下另外两株，再把这一束拿在手中；若许可还没明确，先问清再动手。"),
        ("ACT", "我停止施法，不再取草，带着这一束七星草纸料走回溪口纸坊，向收料人说明来源并交付这一束。日常赶路即可，不替我答应新的委托。"),
    ]
    results, first = [], None
    for number, (mode, action) in enumerate(steps, 1):
        request = {"requestId": str(uuid.uuid4()), "expectedRevision": save["revision"], "mode": mode, "action": action}
        if first is None: first = request
        result = call(f"/api/sessions/{identifier}/turns", {"request": request}, token)
        results.append({"request": request, "result": result})
        (folder / "replay.json").write_text(json.dumps(results, ensure_ascii=False, indent=2))
        print("STEP", number, result["status"], result.get("message"), flush=True)
        if result["status"] != "COMMITTED": raise SystemExit("回放暂停：没有把失败当作正常剧情继续。")
        save = result["save"]
        turn = save["turns"][-1]
        print(turn["narrative"], flush=True)
        print("依据:", turn["ruling"], "查阅:", [r["name"] for r in turn["readings"]], flush=True)
        print("状态:", {"revision": save["revision"], "minutes": save["minutes"], "plants": save["maturePlants"], "grass": save["inventory"]["PLAYER"]["grass"], "goal": save["goal"], "stats": turn["stats"]}, flush=True)
        if number == 1:
            assert save["minutes"] == 0 and save["maturePlants"] == 3 and not save["facts"]
            assert save["inventory"]["PLAYER"]["protection"] == 1
        if number in (2, 3): assert save["maturePlants"] == 3
        if number == 4: assert save["maturePlants"] == 2 and save["inventory"]["PLAYER"]["grass"] == 1
    assert save["goal"] == "DELIVERED"
    assert save["maturePlants"] == 2 and save["inventory"]["SHOP"]["grass"] == 1
    assert save["inventory"]["PLAYER"]["grass"] == 0
    remaining = call("/api/status")["remainingRequests"]
    replay = call(f"/api/sessions/{identifier}/turns", {"request": first}, token)
    assert replay["status"] == "COMMITTED" and replay["save"]["revision"] == 1
    assert call("/api/status")["remainingRequests"] == remaining
    assert call(f"/api/sessions/{identifier}", token=token)["revision"] == 5
    print("REPLAY PASSED; remaining model requests:", remaining, flush=True)

if __name__ == "__main__": main()
