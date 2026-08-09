import json, os, sys, urllib.request

TOKEN = os.environ.get("FIRE_TOKEN", "").strip()
BASE = "https://firestore.googleapis.com/v1/projects/nyachat-in/databases/(default)/documents"

def get(url):
    req = urllib.request.Request(url, headers={"Authorization": f"Bearer {TOKEN}"})
    with urllib.request.urlopen(req) as r:
        return json.load(r)

fam = get(f"{BASE}/families?pageSize=50")
print("=== families ===")
for d in fam.get("documents", []):
    fid = d["name"].split("/")[-1]
    print(f"family={fid} ownerId={d.get('fields',{}).get('ownerId',{}).get('stringValue','-')}")
    try:
        members = get(f"{BASE}/families/{fid}/members")
        for m in members.get("documents", []):
            f = m.get("fields", {})
            has_token = "fcmToken" in f
            print(f"  member={m['name'].split('/')[-1][:8]}.. name={f.get('name',{}).get('stringValue','-')} fcmToken={'YA' if has_token else 'tidak'}")
    except Exception as e:
        print("  members error:", e)
    try:
        msgs = get(f"{BASE}/families/{fid}/messages?pageSize=5")
        for m in msgs.get("documents", []):
            f = m.get("fields", {})
            text = f.get("messageText", {}).get("stringValue", "")[:30]
            ts = f.get("timestamp", {}).get("integerValue", "-")
            print(f"  msg={m['name'].split('/')[-1][:8]}.. ts={ts} text={text!r}")
    except Exception as e:
        print("  messages error:", e)
