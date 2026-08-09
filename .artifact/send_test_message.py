import json, os, time, urllib.request

TOKEN = os.environ.get("FIRE_TOKEN", "").strip()
BASE = "https://firestore.googleapis.com/v1/projects/nyachat-in/databases/(default)/documents"
FAMILY = "55574015"
CLOUD_ID = "e2e-rest-test-006"

def post(url, body):
    req = urllib.request.Request(url, data=json.dumps(body).encode(), headers={
        "Authorization": f"Bearer {TOKEN}", "Content-Type": "application/json"
    })
    with urllib.request.urlopen(req) as r:
        return json.load(r)

body = {
    "fields": {
        "cloudId": {"stringValue": CLOUD_ID},
        "sender": {"stringValue": "Ari Purnomo Aji"},
        "messageText": {"stringValue": "pesan-e2e-rest-987"},
        "timestamp": {"integerValue": str(int(time.time() * 1000))}
    }
}
r = post(f"{BASE}/families/{FAMILY}/messages?documentId={CLOUD_ID}", body)
print(f"Pesan {CLOUD_ID} DITULIS ke Firestore OK (ts={r.get('updateTime','')[:24]})")
