import json, os, urllib.request

TOKEN = os.environ.get("FIRE_TOKEN", "").strip()
BASE = "https://firestore.googleapis.com/v1/projects/nyachat-in/databases/(default)/documents"
FAMILY = "55574015"

def get(url):
    req = urllib.request.Request(url, headers={"Authorization": f"Bearer {TOKEN}"})
    with urllib.request.urlopen(req) as r:
        return json.load(r)

def post(url, body):
    req = urllib.request.Request(url, data=json.dumps(body).encode(), headers={
        "Authorization": f"Bearer {TOKEN}", "Content-Type": "application/json"
    })
    with urllib.request.urlopen(req) as r:
        return json.load(r)

def patch(url, body):
    req = urllib.request.Request(url, data=json.dumps(body).encode(), headers={
        "Authorization": f"Bearer {TOKEN}", "Content-Type": "application/json"
    })
    req.get_method = lambda: "PATCH"
    with urllib.request.urlopen(req) as r:
        return json.load(r)

# 1. Ambil token yang tersimpan di member g6lkldwv (ditulis terakhir oleh device B)
members = get(f"{BASE}/families/{FAMILY}/members")
tokens = {}
for m in members.get("documents", []):
    mid = m["name"].split("/")[-1]
    f = m.get("fields", {})
    tok = f.get("fcmToken", {}).get("stringValue", "")
    name = f.get("name", {}).get("stringValue", "-")
    tokens[mid] = tok
    print(f"member={mid[:8]}.. name={name} token_len={len(tok)}")

# Ambil token terakhir yang valid
valid = [(k, v) for k, v in tokens.items() if v]
if not valid:
    print("TIDAK ADA token valid — stop")
    exit(1)

# Token terakhir = token device yang terakhir menulis (seharusnya B)
token_b = valid[-1][1]
print(f"\nToken terakhir (device B?): {token_b[:40]}...")

# 2. Buat member kedua 'budi-test' dengan token B
budi_uid = "budi-test-uid"
body = {
    "fields": {
        "name": {"stringValue": "Budi"},
        "role": {"stringValue": "member"},
        "fcmToken": {"stringValue": token_b},
        "joinedAt": {"integerValue": str(int(__import__("time").time() * 1000))}
    }
}
try:
    r = post(f"{BASE}/families/{FAMILY}/members/{budi_uid}", body)
    print(f"\nMember {budi_uid} DIBUAT ✓")
except Exception as e:
    print(f"\nCreate member gagal: {e} (mungkin sudah ada, coba update)")

# 3. Pastikan fcmToken member budi-test terisi
try:
    r = patch(f"{BASE}/families/{FAMILY}/members/{budi_uid}?updateMask.fieldPaths=fcmToken", {
        "fields": {"fcmToken": {"stringValue": token_b}}
    })
    print(f"fcmToken budi-test DIUPDATE ✓ ({len(token_b)} chars)")
except Exception as e:
    print(f"Update token budi-test gagal: {e}")

# 4. Verifikasi final
members = get(f"{BASE}/families/{FAMILY}/members")
for m in members.get("documents", []):
    f = m.get("fields", {})
    name = f.get("name", {}).get("stringValue", "-")
    tok = f.get("fcmToken", {}).get("stringValue", "")
    print(f"  {name} | token_len={len(tok)}")
