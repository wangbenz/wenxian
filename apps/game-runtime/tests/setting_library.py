"""资料设定单元检查专用的明确合成条目；不作为玩家资料、模拟试玩或真实语义证据。"""

import hashlib

import httpx

NAMES = {("realms", "lianqi"): "炼气", ("realms", "zhuji"): "筑基",
         ("items", "medicinal-spirit-herb"): "药用灵草",
         ("arts", "object-control"): "控物术", ("items", "storage-pouch"): "储物袋",
         ("items", "basic-protection-talisman"): "简式护身符"}


def setting_response(request):
    path = request.url.path
    for (category, key), name in NAMES.items():
        historical = f"/revisions/{category}:{key}:" in path
        if path.endswith(f"/{category}/{key}/") or historical:
            entry = {"key": key, "name": name, "entry_kind": "overview", "revision": 2,
                     "content_hash": hashlib.sha256(f"unit:{category}:{key}".encode()).hexdigest(),
                     "description": f"合成单元资料：{name}", "usage": "仅用于结构检查",
                     "limitations": "不用于真实玩法", "source": "明确的单元合成资料"}
            return httpx.Response(200, json={"revision": 2, "content_hash": entry["content_hash"],
                                           "content": entry} if historical else entry)
    return None
