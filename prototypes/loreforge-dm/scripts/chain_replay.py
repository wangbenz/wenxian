"""连续场景的受控回放；真实调用需另行批准预算。证据和凭据仅写入忽略的.data。"""
import argparse
import json
import uuid
from datetime import datetime
from pathlib import Path
from replay import call

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("route", choices=["quick", "careful"])
    args = parser.parse_args()
    folder = Path(".data/evidence") / (datetime.now().strftime("%Y%m%d-%H%M%S") + "-chain-" + args.route)
    folder.mkdir(parents=True)
    created = call("/api/sessions", {"scenario": "paper-journey"})
    identifier, token, save = created["id"], created["token"], created["save"]
    access = folder / "access.json"
    access.write_text(json.dumps({"id": identifier, "token": token}))
    access.chmod(0o600)
    steps = [
        ("ACT", "我把纸坊便条给程叔看，说明只取一株，问是否可以帮他把落地木塞放回漏水的槽口，换取许可。这一步只谈条件，不移动木塞或采草。"),
        ("ACT", "我站在原地，对三步外落地的木塞施放控物术，将它缓慢放回四步外的水槽缺口，完成后收功。请程叔检查修补，确认我是否可以剪取一株。暂不剪草。"),
        ("ACT", "如果采收许可已经明确，我用小剪只剪一株成熟草的纸料，拿在手中，留下另外两株。然后沿路走12分钟到纸坊，出示便条，向沈师傅说明请他加工这批纸料，先听清两种制纸与制符服务。本轮不制纸、不制符。"),
        ("ACT", "我选择赶制：请沈师傅把这一束纸料加工成一张完好的符纸，再用这张纸和他的一份符墨制作一张护身符交给我。确认这是他代制的，不代表我学会制符；不要多做或替我出发。" if args.route == "quick" else "我选择仔细加工：请沈师傅用这一束纸料制成两张符纸，再逐张用他两份符墨制成两张护身符交给我。我愿意花这段工时，不赶制，也不要替我出发。"),
        ("ACT", "我带着现有成品沿路走8分钟到旧驿亭，先看清前廊和后坡的入口以及路引所在。本轮不激发符、不穿过前廊，也不去渡口。"),
        ("ASK", "按护身符的真实定义，它能替我通过这个前廊吗？只能保护谁、可以抵挡几次？按目前已经过去的时间，取路引后还赶得上船吗？这次只问，不行动。"),
        ("ACT", "我手持一张护身符，按学过的短诀注入法力激发，随即在护光有效时跨过这几步前廊，取出封存路引。如果此刻走到渡口仍在70分钟以内，我再用3分钟赶到并交验路引上船；不要替我答应新的委托。" if args.route == "quick" else "我决定不赶船，先用一张护身符，手持注入法力念明已学短诀，随即在护光有效时跨过前廊，取到自己的路引后留宿驿亭。另一张符保留，不使用，不接受新义务。"),
    ]
    results = []
    try:
        for index, (mode, action) in enumerate(steps, 1):
            req = {"requestId": str(uuid.uuid4()), "expectedRevision": save["revision"], "mode": mode, "action": action}
            before = save
            result = call(f"/api/sessions/{identifier}/turns", {"request": req}, token)
            results.append({"request": req, "result": result})
            (folder / "replay.json").write_text(json.dumps(results, ensure_ascii=False, indent=2))
            print("STEP", index, result["status"], result.get("message"), flush=True)
            if result["status"] != "COMMITTED": raise SystemExit("停止：失败没有被当作剧情继续。")
            save = result["save"]
            print(save["turns"][-1]["narrative"], flush=True)
            print("回执:", save["turns"][-1]["receipt"], flush=True)
            if index in (1, 2): assert save["inventory"]["GARDEN"]["living-grass"] == 3
            if index == 3: assert save["inventory"]["PLAYER"]["grass"] == 1 and save["inventory"]["PLAYER"]["paper"] == 0
            if index == 4: assert save["inventory"]["PLAYER"]["protection"] == (1 if args.route == "quick" else 2)
            if mode == "ASK":
                for key in ("inventory", "minutes", "condition", "location", "facts", "adventure"): assert save[key] == before[key], key
        assert save["goal"] == "DELIVERED"
        assert save["inventory"]["PLAYER"]["protection"] == (0 if args.route == "quick" else 1)
        assert save["adventure"]["ending"] == ("赶上渡船" if args.route == "quick" else "留宿驿亭")
        remaining = call("/api/status")["remainingRequests"]
        duplicate = call(f"/api/sessions/{identifier}/turns", {"request": results[-1]["request"]}, token)
        assert duplicate["save"] == save and call("/api/status")["remainingRequests"] == remaining
        print("PASSED", args.route, "remaining:", remaining, "evidence:", folder, flush=True)
    finally:
        evidence = call(f"/api/sessions/{identifier}/inspection", token=token)
        (folder / "inspection.json").write_text(json.dumps(evidence, ensure_ascii=False, indent=2))

if __name__ == "__main__": main()
