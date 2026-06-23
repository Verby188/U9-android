// ══════════════════════════════════════════════════════
//  config.js — Configuration U9
//  Placer dans : app/src/main/assets/config.js
//  Versionnable sur GitHub (pas de secret ici)
// ══════════════════════════════════════════════════════
window.APP_CONFIG = {

  // Clé API Firebase (même projet que Cinq Couronnes : u9-game)
  // Firebase Console → Project Settings → General → Web API Key
  FIREBASE_KEY: 'AIzaSyCPVnGohf_etSJYIFCSnWxjIB2RPX5rmnU',

  // URL Realtime Database
  DB_URL: 'https://u9-game-default-rtdb.europe-west1.firebasedatabase.app',

  // URL du Cloudflare Worker FCM
  // Même Worker que Cinq Couronnes (il gère les deux apps)
  FCM_WORKER_URL: 'https://cinqrois-fcm.verbatim1889.workers.dev',

  // Schéma deep link pour les invitations U9
  DEEP_LINK_SCHEME: 'u9game',

  // Page d'invitation (à créer sur GitHub Pages)
  INVITE_PAGE: 'https://Verby188.github.io/u9/join.html',

};
