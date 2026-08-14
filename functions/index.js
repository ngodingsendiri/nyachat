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
// NOTIFIKASI CHAT (FASE 3 item 3.7)
// ============================================================================

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
    const text = String(data.messageText || '').trim();
    const body = text ? text.slice(0, 220) : 'Pesan baru';

    // Kumpulkan token FCM semua anggota yang pernah disinkronkan.
    const membersSnap = await admin.firestore()
      .collection('families').doc(familyId)
      .collection('members').get();
    const recipients = []; // { uid, token }
    membersSnap.forEach((doc) => {
      const t = doc.data().fcmToken;
      if (typeof t === 'string' && t.length > 0) {
        recipients.push({ uid: doc.id, token: t });
      }
    });
    if (recipients.length === 0) return;

    const tokens = recipients.map((r) => r.token);
    const message = {
      data: {
        sender: sender,
        body: body,
        cloudId: String(event.params.cloudId)
      }
    };

    let response;
    try {
      response = await admin.messaging().sendEachForMulticast({
        tokens: tokens,
        data: message.data
      });
    } catch (err) {
      logger.warn('Gagal kirim notifikasi multicast', err);
      return;
    }

    // Bersihkan token yang tidak valid (app di-uninstall / token kedaluwarsa)
    // supaya kiriman berikutnya lebih bersih & biaya FCM tidak terbuang.
    const invalidUids = [];
    response.responses.forEach((r, i) => {
      const rec = recipients[i];
      if (!rec) return;
      const err = r.error || {};
      if (!r.success) {
        invalidUids.push(rec.uid);
        console.log('FCM gagal uid=' + rec.uid.slice(0, 10) +
          ' code=' + (err.code || '?') +
          ' msg=' + (err.message || '?'));
      } else {
        console.log('FCM OK uid=' + rec.uid.slice(0, 10));
      }
    });
    console.log('Multicast total=' + recipients.length +
      ' gagal=' + invalidUids.length + ' sender=' + sender);
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
);
