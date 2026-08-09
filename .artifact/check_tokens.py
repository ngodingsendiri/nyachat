import json, os, urllib.request

TOKEN = os.environ.get("FIRE_TOKEN", "").strip()
BASE = "https://firestore.googleapis.com/v1/projects/nyachat-in/databases/(default)/documents"

def get(url):
    req = urllib.request.Request(url, headers={"Authorization": f"Bearer {TOKEN}"})
    with urllib.request.urlopen(req) as r:
        return json.load(r)

fam = get(f"{BASE}/families?pageSize=50")
for d in fam.get("documents", []):
    fid = d["name"].split("/")[-1]
    print(f"=== family={fid} ownerId={d.get('fields',{}).get('ownerId',{}).get('stringValue','-')} ===")
    try:
        members = get(f"{BASE}/families/{fid}/members")
        for m in members.get("documents", []):
            f = m.get("fields", {})
            name = f.get("name", {}).get("stringValue", "-")
            tok = f.get("fcmToken", {}).get("stringValue", None)
            if tok:
                print(f"  {name} | fcmToken=LENGKAP({len(tok)} chars): {tok[:45]}...")
            else:
                print(f"  {name} | fcmToken={'KOSONG (field ada)' if 'fcmToken' in f else 'TIDAK ADA (field tidak ada)'}")
    except Exception as e:
        print("  members error:", e)
