/**
 * Nyachat — Cloud Functions (Firebase Functions v2).
 *
 * onMessageWrite (FASE 3 item 3.7):
 * Setiap pesan baru di families/{familyId}/messages/{cloudId} dikirim sebagai
 * FCM *data message* ke semua anggota workspace (kecuali yang tidak punya
 * token). Kebijakan TAMPIL dijalankan di app (FirebaseMessagingService):
 *   - toggle "Notifikasi chat" di Settings,
 *   - skip pesan dari diri sendiri (sender == userName lokal).
 * Cloud hanya menyalurkan payload — tidak menentukan tampilan/suara.
 */
const { onDocumentWritten } = require('firebase-functions/v2/firestore');
const { onCall, HttpsError } = require('firebase-functions/v2/https');
const { defineSecret } = require('firebase-functions/params');
// CATATAN: firebase-functions/logger mengekspor objek logger LANGSUNG, BUKAN
// properti bernama `logger` — `const { logger } = require(...)` menghasilkan
// undefined dan membuat logger.warn/error crash di runtime (bug FASE 4
// ditemukan saat live test: TypeError reading 'warn').
const logger = require('firebase-functions/logger');
const admin = require('firebase-admin');

admin.initializeApp();

// ============================================================================
// RELAY AI (server-owned API keys) — FASE 4
// ----------------------------------------------------------------------------
// Latar belakang: app memakai BYOK (Bring Your Own Key) — user mengisi kunci
// Gemini/OpenRouter sendiri di Pengaturan. Kalau user TIDAK mengisi kunci,
// deteksi transaksi jatuh ke mesin heuristik offline yang kaku.
//
// Solusi: Cloud Function `aiComplete` memegang KUNCI MILIK SERVER (disimpan
// sebagai Firebase Functions secrets — TIDAK pernah dikompilasi ke APK). App
// yang sudah login cukup memanggil fungsi ini (auth Firebase diverifikasi
// otomatis oleh protokol callable); server yang memanggil AI (OpenRouter gratis
// → Gemini), lalu mengembalikan teks mentah. App tetap yang membangun prompt &
// mem-parse JSON — satu fungsi generik untuk parse transaksi, saran cepat,
// laporan audit, analisis bulanan, dan tanya AI.
//
// Setup satu kali:
//   firebase functions:secrets:set OPENROUTER_API_KEY
//   firebase functions:secrets:set GEMINI_API_KEY
//   firebase deploy --only functions
//
// Model "opencode zen" yang diminta user tidak ditemukan di katalog OpenRouter
// (per 2026-08-10) — daftar model gratis terverifikasi dipakai sebagai gantinya.
// ============================================================================

const OPENROUTER_SECRET = defineSecret('OPENROUTER_API_KEY');
const GEMINI_SECRET = defineSecret('GEMINI_API_KEY');

/** Model gratis OpenRouter (terverifikasi aktif per 2026-08-10). */
const FREE_MODELS = [
  'google/gemma-4-31b-it:free',
  'google/gemma-4-26b-a4b-it:free',
  'openai/gpt-oss-20b:free',
  'nvidia/nemotron-3-ultra-550b-a55b:free',
  'inclusionai/ling-3.0-tiny:free',
  'poolside/laguna-xs-2.1:free',
  'openrouter/free',
];

/**
 * Baca secret Firebase dengan fallback ke env var dengan nama yang sama
 * (memudahkan dev lokal / CI tanpa Secret Manager — deploy biasa cukup
 * `firebase functions:secrets:set`). defineSecret.value() melempar bila
 * secret belum diset.
 */
function readSecret(secret) {
  try {
    const v = secret.value();
    if (typeof v === 'string' && v.length > 0) return v;
  } catch (e) {
    // secret belum diset — lanjut cek env di bawah
  }
  const env = process.env[secret.name];
  return typeof env === 'string' && env.length > 0 ? env : null;
}

async function callOpenRouter(apiKey, model, prompt, imageBase64) {
  const content = imageBase64
    ? [
        { type: 'text', text: prompt },
        { type: 'image_url', image_url: { url: `data:image/jpeg;base64,${imageBase64}` } },
      ]
    : prompt;
  const resp = await fetch('https://openrouter.ai/api/v1/chat/completions', {
    method: 'POST',
    headers: {
      Authorization: `Bearer ${apiKey}`,
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({
      model,
      messages: [{ role: 'user', content }],
      temperature: 0.2,
    }),
  });
  if (!resp.ok) throw new Error(`OpenRouter HTTP ${resp.status}`);
  const json = await resp.json();
  const text = json.choices && json.choices[0] && json.choices[0].message
    ? String(json.choices[0].message.content || '')
    : '';
  return text.trim() || null;
}

async function callGemini(apiKey, prompt, imageBase64) {
  const parts = [{ text: prompt }];
  if (imageBase64) {
    parts.push({ inline_data: { mime_type: 'image/jpeg', data: imageBase64 } });
  }
  const url =
    'https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash:generateContent?key=' +
    encodeURIComponent(apiKey);
  const resp = await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      contents: [{ parts }],
      generationConfig: { temperature: 0.2 },
    }),
  });
  if (!resp.ok) throw new Error(`Gemini HTTP ${resp.status}`);
  const json = await resp.json();
  const text =
    json.candidates &&
    json.candidates[0] &&
    json.candidates[0].content &&
    json.candidates[0].content.parts
      ? String(json.candidates[0].content.parts[0].text || '')
      : '';
  return text.trim() || null;
}

// ============================================================================
// BATAS PENGGUNAAN AI (P1#2 — audit 2026-08-14)
// ----------------------------------------------------------------------------
// aiComplete hanya butuh auth Google (bukan keanggotaan keluarga mana pun) —
// tanpa batas, siapa pun bisa memanggil berulang kali dan menguras kuota AI
// server (biaya tak terkendali). Pembatas in-memory sliding-window per-uid.
// CATATAN: per-instance — abuse lintas-instance masih mungkin; untuk produksi
// skala besar ganti dengan counter Firestore (tulis 1 doc/menit/uid).
// ============================================================================
const RATE_LIMIT_WINDOW_MS = 60_000;      // jendela 1 menit
const RATE_LIMIT_MAX_CALLS = 30;          // maks 30 panggilan/menit/uid
const MAX_PROMPT_CHARS = 6000;            // ~1.500 token — prompt AI keluarga
const MAX_IMAGE_BASE64_CHARS = 3_000_000; // ~2,2 MB biner foto nota

const rateBuckets = new Map(); // uid -> array timestamp panggilan
function allowCall(uid, now) {
  const recent = (rateBuckets.get(uid) || []).filter((t) => now - t < RATE_LIMIT_WINDOW_MS);
  if (recent.length >= RATE_LIMIT_MAX_CALLS) {
    rateBuckets.set(uid, recent);
    return false;
  }
  recent.push(now);
  rateBuckets.set(uid, recent);
  return true;
}

/**
 * aiComplete — relay AI generik. Callable = auth Firebase otomatis diverifikasi
 * (request.auth non-null hanya untuk user terautentikasi). Body: { prompt,
 * imageBase64? }. Response: { text } — null saat semua penyedia gagal.
 */
exports.aiComplete = onCall(
  {
    secrets: [OPENROUTER_SECRET, GEMINI_SECRET],
    timeoutSeconds: 55,
  },
  async (request) => {
    if (!request.auth) {
      throw new HttpsError('unauthenticated', 'Harus login untuk memakai AI server.');
    }
    // P1#2: batas laju per-uid — melindungi kuota AI server dari penyalahgunaan.
    if (!allowCall(request.auth.uid, Date.now())) {
      throw new HttpsError('resource-exhausted', 'Terlalu banyak panggilan AI. Coba lagi sebentar.');
    }
    const data = request.data || {};
    const prompt = String(data.prompt || '').trim();
    if (!prompt) {
      throw new HttpsError('invalid-argument', 'Prompt tidak boleh kosong.');
    }
    if (prompt.length > MAX_PROMPT_CHARS) {
      throw new HttpsError('invalid-argument', 'Prompt terlalu panjang.');
    }
    const imageBase64 =
      typeof data.imageBase64 === 'string' && data.imageBase64.length > 0
        ? data.imageBase64
        : null;
    if (imageBase64 && imageBase64.length > MAX_IMAGE_BASE64_CHARS) {
      throw new HttpsError('invalid-argument', 'Gambar terlalu besar.');
    }

    // 1) OpenRouter (server key) — model gratis dengan rotasi otomatis
    const orKey = readSecret(OPENROUTER_SECRET);
    if (orKey) {
      for (const model of FREE_MODELS) {
        try {
          const text = await callOpenRouter(orKey, model, prompt, imageBase64);
          if (text) {
            // P2#5 (audit 2026-08-14): JANGAN log isi output — output AI rekap
            // berisi data finansial pengguna (privasi). Cukup model + panjang.
            logger.info('Relay OK model=' + model + ' len=' + text.length);
            return { text };
          }
        } catch (e) {
          logger.warn('Relay OpenRouter gagal: ' + model, e);
        }
      }
    }

    // 2) Gemini (server key)
    const gemKey = readSecret(GEMINI_SECRET);
    if (gemKey) {
      try {
        const text = await callGemini(gemKey, prompt, imageBase64);
        if (text) return { text };
      } catch (e) {
        logger.warn('Relay Gemini gagal', e);
      }
    }

    // Semua penyedia gagal / tidak ada key server → null (app lanjut heuristik)
    return { text: null };
  }
);

// ============================================================================
// NOTIFIKASI CHAT & KEANGGOTAAN (FASE 3 item 3.7 + r1.6.0)
// ============================================================================

// Batas anggota per plan — HARUS sinkron dengan Constants.Limits di app.
const PLAN_FREE = 'free';
const PLAN_PRO = 'pro';
const LIMIT_FREE = 2;
const LIMIT_PRO = 6;

/** Token FCM anggota, null bila kosong/rusak. */
function tokenOf(memberDoc) {
  const t = memberDoc.data().fcmToken;
  return typeof t === 'string' && t.length > 0 ? t : null;
}

/**
 * Kirim data message multicast ke token FCM & bersihkan token invalid
 * (app di-uninstall / token kedaluwarsa) supaya kiriman berikutnya lebih
 * bersih & biaya FCM tidak terbuang. Dipakai notifyChatMessage &
 * handleJoinRequest (DRY).
 * @param {Array<{uid:string, token:string}>} tokensByUid
 */
async function sendMulticastAndCleanup(familyId, tokensByUid, data) {
  if (tokensByUid.length === 0) return;
  const tokens = tokensByUid.map((r) => r.token);
  let response;
  try {
    response = await admin.messaging().sendEachForMulticast({ tokens: tokens, data: data });
  } catch (err) {
    logger.warn('Gagal kirim notifikasi multicast', err);
    return;
  }
  const invalidUids = [];
  response.responses.forEach((r, i) => {
    const rec = tokensByUid[i];
    if (!rec) return;
    const err = r.error || {};
    if (!r.success) {
      invalidUids.push(rec.uid);
      console.log('FCM gagal uid=' + rec.uid.slice(0, 10) +
        ' code=' + (err.code || '?') +
        ' msg=' + (err.message || '?'));
    }
  });
  console.log('Multicast total=' + tokensByUid.length +
    ' gagal=' + invalidUids.length);
  if (invalidUids.length > 0) {
    const batch = admin.firestore().batch();
    invalidUids.forEach((uid) => {
      batch.update(
        admin.firestore()
          .collection('families').doc(familyId).collection('members').doc(uid),
        { fcmToken: admin.firestore.FieldValue.delete() }
      );
    });
    await batch.commit()
      .catch((err) => logger.warn('Hapus token FCM invalid gagal', err));
  }
}

// Nama fungsi sengaja BUKAN onMessageWrite — deploy percobaan pertama sempat
// membuat resource dengan trigger HTTPS yang tidak bisa di-update ke trigger
// background ("Changing from an HTTPS function..."). Nama baru = resource baru.
exports.notifyChatMessage = onDocumentWritten(
  'families/{familyId}/messages/{cloudId}',
  async (event) => {
    const after = event.data && event.data.after;
    if (!after || !after.exists) return; // pesan dihapus → tanpa notifikasi
    // Sempurnakan 2026-08-14: EDIT pesan (before ada) → JANGAN notifikasi ulang
    // ke semua anggota — sebelumnya tiap edit mengirim notifikasi duplikat.
    const before = event.data && event.data.before;
    if (before && before.exists) return;
    const data = after.data();
    if (!data) return;
    const familyId = event.params.familyId;

    const sender = String(data.sender || 'Nyachat');
    // r1.7.0 (E2EE): konten pesan TIDAK dikirim ke cloud apa adanya lagi —
    // sejak aktivasi E2EE body = ciphertext `enc` yang tidak terbaca server.
    // Payload FCM dibuat GENERIK ("Pesan baru dari X") tanpa teks/isi; konten
    // hanya bisa dilihat di dalam app setelah didekripsi (server tidak pernah
    // memegang plaintext). Notifikasi lama (pra E2EE) tetap dikirim generik —
    // konsisten & tidak membocorkan isi.
    const senderUid = String(data.senderUid || '');

    // Kumpulkan token FCM semua anggota yang pernah disinkronkan.
    const membersSnap = await admin.firestore()
      .collection('families').doc(familyId)
      .collection('members').get();
    const recipients = []; // { uid, token }
    membersSnap.forEach((doc) => {
      const t = tokenOf(doc);
      // r1.6.1: jangan kirim ke penulis sendiri (hemat FCM & tidak perlu
      // di-skip lagi di app). Bila senderUid kosong (data lama) kirim semua.
      if (!t || (senderUid && doc.id === senderUid)) return;
      recipients.push({ uid: doc.id, token: t });
    });
    await sendMulticastAndCleanup(familyId, recipients, {
      sender: sender,
      cloudId: String(event.params.cloudId),
      senderUid: senderUid
    });
  }
);

/**
 * r1.7.0 — CHAT EPHEMERAL ala WhatsApp: hapus pesan dari server begitu SEMUA
 * perangkat anggota menulis ACK di subkoleksi deliveries/{uid} (perangkat
 * menerima & menyimpan pesan di Room — server tidak menyimpan konten).
 *
 * Trigger: tiap tulis di `families/{familyId}/messages/{cloudId}/deliveries/{uid}`.
 *  1. Pesan plaintext lama (msgVersion=0) → TIDAK pernah dihapus (riwayat lama
 *     tetap tersimpan & tersinkron antar perangkat).
 *  2. Pesan terenkripsi (msgVersion=1): hapus bila (a) semua anggota punya ACK,
 *     ATAU (b) TTL: pesan berumur > TTL_MS sejak timestamp (jaring pengaman
 *     untuk perangkat yang tidak pernah online lagi).
 *  3. Hapus: doc pesan + subkoleksi ACK-nya + foto blob Storage (best-effort).
 */
exports.cleanupDeliveredMessage = onDocumentWritten(
  'families/{familyId}/messages/{cloudId}/deliveries/{uid}',
  async (event) => {
    const familyId = event.params.familyId;
    const cloudId = event.params.cloudId;
    const messageRef = admin.firestore()
      .collection('families').doc(familyId)
      .collection('messages').doc(cloudId);

    const messageSnap = await messageRef.get();
    if (!messageSnap.exists) return; // sudah dihapus
    const data = messageSnap.data() || {};
    // Hanya pesan terenkripsi (post-E2EE) yang ephemeral.
    if (Number(data.msgVersion || 0) !== 1) return;
    const TTL_MS = 90 * 24 * 60 * 60 * 1000; // 90 hari jaring pengaman
    const old = Number(data.timestamp || 0) > 0 &&
      Date.now() - Number(data.timestamp) > TTL_MS;
    if (!old) {
      // Butuh semua member punya ACK (termasuk pengirim — ia juga menerima
      // pesannya sendiri lewat snapshot listener & menulis ACK).
      const [membersSnap, deliveriesSnap] = await Promise.all([
        admin.firestore()
          .collection('families').doc(familyId).collection('members').get(),
        messageRef.collection('deliveries').get()
      ]);
      const memberCount = membersSnap.size;
      const ackedUids = new Set(deliveriesSnap.docs.map((d) => d.id));
      const allAcked = memberCount > 0 &&
        membersSnap.docs.every((d) => ackedUids.has(d.id));
      if (!allAcked) return;
    }

    // Hapus pesan + ACK + foto Storage (best-effort, jangan menggagalkan doc).
    const batch = admin.firestore().batch();
    batch.delete(messageRef);
    const deliveries = await messageRef.collection('deliveries').get();
    deliveries.docs.forEach((d) => batch.delete(d.ref));
    await batch.commit()
      .catch((err) => logger.warn('Hapus pesan+ACK gagal', err));
    const storagePath =
      'families/' + familyId + '/messages/' + cloudId + '.jpg';
    await admin.storage().bucket().file(storagePath).delete()
      .catch(() => { /* foto belum ada / sudah dihapus */ });
  }
);

/**
 * Notifikasi keanggotaan (r1.6.0) — trigger pada join request:
 * - CREATE: permintaan bergabung masuk → notifikasi PEMILIK (jika workspace
 *   masih muat). Body memakai nama custom workspace bila ada.
 * - DELETE: keputusan owner (approved/rejected) → notifikasi PEMOHON via token
 *   yang disimpan di doc request saat requestJoin. Request yang dicabut sendiri
 *   (tanpa status) tidak menghasilkan notifikasi.
 * Payload berisi `type` sehingga app bisa membedakan dari notifikasi chat.
 */
exports.handleJoinRequest = onDocumentWritten(
  'families/{familyId}/joinRequests/{uid}',
  async (event) => {
    const before = event.data && event.data.before;
    const after = event.data && event.data.after;
    const familyId = event.params.familyId;
    const uid = event.params.uid;
    const famRef = admin.firestore()
      .collection('families').doc(familyId);

    // EVENT DELETE — keputusan owner.
    if (after && !after.exists && before && before.exists) {
      const req = before.data() || {};
      const token = typeof req.fcmToken === 'string' && req.fcmToken.length > 0
        ? req.fcmToken
        : null;
      if (!token) return;
      // Approve → member doc ada (ditulis atomik bersama delete request).
      // Reject → ditandai `status='rejected'` sebelum delete.
      // Selain itu (dicabut sendiri) → tanpa notifikasi.
      let approved = false;
      try {
        const memberSnap = await famRef.collection('members').doc(uid).get();
        approved = memberSnap.exists;
      } catch (err) {
        logger.warn('handleJoinRequest: cek member gagal', err);
        return;
      }
      if (!approved && req.status !== 'rejected') return;
      let familyName = '';
      try {
        const fam = await famRef.get();
        if (fam.exists) familyName = String(fam.data().name || '');
      } catch (err) {
        logger.warn('handleJoinRequest: baca keluarga gagal', err);
      }
      await sendMulticastAndCleanup(familyId, [{ uid, token }], {
        type: 'join_decision',
        approved: approved ? '1' : '0',
        requesterUid: uid,
        familyName: familyName
      });
      return;
    }

    // Bukan CREATE (hanya update status oleh owner) → tanpa notifikasi.
    if (!after || !after.exists) return;
    if (before && before.exists) return;

    // CREATE — permintaan masuk. Cek kapasitas dulu: workspace penuh → tanpa
    // notifikasi (owner hanya akan melihat permintaan yang tak bisa disetujui).
    try {
      const fam = await famRef.get();
      if (!fam.exists) return;
      const famData = fam.data() || {};
      const limit = famData.plan === PLAN_PRO ? LIMIT_PRO : LIMIT_FREE;
      const membersSnap = await famRef.collection('members').get();
      if (membersSnap.size >= limit) return;

      const ownerTokens = [];
      membersSnap.forEach((doc) => {
        if (doc.data().role === 'owner') {
          const t = tokenOf(doc);
          if (t) ownerTokens.push({ uid: doc.id, token: t });
        }
      });
      if (ownerTokens.length === 0) return;
      const req = after.data() || {};
      await sendMulticastAndCleanup(familyId, ownerTokens, {
        type: 'join_request',
        requesterName: String(req.name || ''),
        requesterEmail: String(req.email || ''),
        requesterUid: uid,
        familyName: String(famData.name || '')
      });
    } catch (err) {
      logger.warn('handleJoinRequest gagal', err);
    }
  }
);
