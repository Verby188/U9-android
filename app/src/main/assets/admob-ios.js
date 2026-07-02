/* ============================================================
   U9 — Intégration AdMob iOS (plugin @capacitor-community/admob v8)
   ------------------------------------------------------------
   - N'agit QUE sur iOS (Capacitor). Inerte sur Android et web.
   - Bannière en bas + interstitiel préchargé.
   - Interstitiel déclenché : avant l'écran "Partie terminée"
     (détecté via MutationObserver) et au clic "Quitter"
     (via window.U9_showInterstitial(), appelé depuis le bouton).
   - Tout est protégé par try/catch : une pub qui échoue ne doit
     jamais bloquer le jeu.
   ============================================================ */
(function () {
  "use strict";

  // --- Identifiants AdMob iOS U9 (publics) ---
  var BANNER_ID = "ca-app-pub-6145497382360748/7827950328";
  var INTERSTITIAL_ID = "ca-app-pub-6145497382360748/1817075743";

  // Passe à true UNIQUEMENT pour tester l'affichage avec les pubs de test Google.
  // En production : false (sinon AdMob ne comptabilise pas les vraies impressions).
  var IS_TESTING = false;

  var interstitialReady = false;
  var lastInterstitialAt = 0;
  // Anti-spam : pas plus d'un interstitiel toutes les 60 s.
  var MIN_INTERVAL_MS = 60 * 1000;

  function isIOS() {
    return !!(window.Capacitor &&
      typeof window.Capacitor.getPlatform === "function" &&
      window.Capacitor.getPlatform() === "ios");
  }

  function admob() {
    return window.Capacitor && window.Capacitor.Plugins
      ? window.Capacitor.Plugins.AdMob
      : null;
  }

  // --- Initialisation : ATT -> SDK -> bannière -> précharge interstitiel ---
  async function initAdMob() {
    if (!isIOS()) return;
    var AdMob = admob();
    if (!AdMob) {
      console.warn("[AdMob] plugin introuvable");
      return;
    }
    try {
      await AdMob.initialize();

      // App Tracking Transparency : demander AVANT de servir des pubs.
      try {
        var st = await AdMob.trackingAuthorizationStatus();
        if (st && st.status === "notDetermined") {
          await AdMob.requestTrackingAuthorization();
        }
      } catch (e) {
        console.warn("[AdMob] ATT indisponible", e);
      }

      // Bannière adaptative en bas.
      try {
        await AdMob.showBanner({
          adId: BANNER_ID,
          adSize: "ADAPTIVE_BANNER",
          position: "BOTTOM_CENTER",
          margin: 0,
          isTesting: IS_TESTING
        });
      } catch (e) {
        console.warn("[AdMob] showBanner KO", e);
      }

      await prepareInterstitial();
    } catch (e) {
      console.warn("[AdMob] init KO", e);
    }
  }

  async function prepareInterstitial() {
    if (!isIOS()) return;
    var AdMob = admob();
    if (!AdMob) return;
    try {
      await AdMob.prepareInterstitial({
        adId: INTERSTITIAL_ID,
        isTesting: IS_TESTING
      });
      interstitialReady = true;
    } catch (e) {
      interstitialReady = false;
      console.warn("[AdMob] prepareInterstitial KO", e);
    }
  }

  // Exposé sur window pour être appelé depuis le bouton "Quitter".
  // Affiche l'interstitiel s'il est prêt, puis en recharge un.
  window.U9_showInterstitial = async function () {
    if (!isIOS()) return;
    var AdMob = admob();
    if (!AdMob) return;

    var now = Date.now();
    if (now - lastInterstitialAt < MIN_INTERVAL_MS) return; // anti-spam
    if (!interstitialReady) { prepareInterstitial(); return; }

    lastInterstitialAt = now;
    interstitialReady = false;
    try {
      await AdMob.showInterstitial();
    } catch (e) {
      console.warn("[AdMob] showInterstitial KO", e);
    }
    prepareInterstitial(); // recharger pour la prochaine fois
  };

  // --- Détection de l'écran "Partie terminée" via le DOM ---
  // Plus fiable que de modifier le React compilé (timing de rendu).
  var gameOverShown = false;
  function watchGameOver() {
    if (!isIOS()) return;
    var observer = new MutationObserver(function () {
      var txt = document.body ? document.body.innerText : "";
      var over = txt.indexOf("Partie terminée") !== -1;
      if (over && !gameOverShown) {
        gameOverShown = true;
        window.U9_showInterstitial();
      } else if (!over && gameOverShown) {
        gameOverShown = false; // réarme pour la partie suivante
      }
    });
    observer.observe(document.body, { childList: true, subtree: true });
  }

  // --- Démarrage ---
    // --- Hauteur de banniere (evite le recouvrement des cartes/boutons) ---
  function setAdHeight(px){
    var h = (px && px > 0) ? px : 0;
    document.documentElement.style.setProperty('--u9-ad-h', h + 'px');
  }
  function setupAdHeight(){
    if (!isIOS()) return;
    try {
      var P = Capacitor.Plugins.AdMob;
      P.addListener('bannerAdSizeChanged', function(info){
        var h = info && (info.height != null ? info.height : (info.size && info.size.height));
        if (h && h > 0) setAdHeight(h);
      });
    } catch(e){}
    // Repli : reserve une hauteur standard si l'evenement ne se declenche pas
    setTimeout(function(){
      var cur = getComputedStyle(document.documentElement).getPropertyValue('--u9-ad-h').trim();
      if (!cur || cur === '0px') setAdHeight(60);
    }, 3000);
  }

  function start() {
    if (!isIOS()) return; // sortie immédiate sur Android / web
    initAdMob();
    setupAdHeight();
    watchGameOver();
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", start);
  } else {
    start();
  }
})();
