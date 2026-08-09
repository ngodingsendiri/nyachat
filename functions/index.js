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
const { logger } = require('firebase-functions/logger');
const admin = require('firebase-admin');

admin.initializeApp();

// Nama fungsi sengaja BUKAN onMessageWrite — deploy percobaan pertama sempat
// membuat resource dengan trigger HTTPS yang tidak bisa di-update ke trigger
// background ("Changing from an HTTPS function..."). Nama baru = resource baru.
exports.notifyChatMessage = onDocumentWritten(
  'families/{familyId}/messages/{cloudId}',
  async (event) => {
    const after = event.data && event.data.after;
    if (!after || !after.exists) return; // pesan dihapus → tanpa notifikasi
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
