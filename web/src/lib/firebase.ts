import { initializeApp, getApps, type FirebaseApp } from "firebase/app";
import { initializeAppCheck, ReCaptchaEnterpriseProvider } from "firebase/app-check";
import { getAuth, signInAnonymously, onAuthStateChanged, type User } from "firebase/auth";
import { initializeFirestore, persistentLocalCache, persistentMultipleTabManager, type Firestore } from "firebase/firestore";
import { getStorage, type FirebaseStorage } from "firebase/storage";
import { getAI, GoogleAIBackend, type AI } from "firebase/ai";

const config = {
  apiKey: process.env.NEXT_PUBLIC_FIREBASE_API_KEY,
  authDomain: process.env.NEXT_PUBLIC_FIREBASE_AUTH_DOMAIN,
  projectId: process.env.NEXT_PUBLIC_FIREBASE_PROJECT_ID,
  storageBucket: process.env.NEXT_PUBLIC_FIREBASE_STORAGE_BUCKET,
  messagingSenderId: process.env.NEXT_PUBLIC_FIREBASE_MESSAGING_SENDER_ID,
  appId: process.env.NEXT_PUBLIC_FIREBASE_APP_ID,
};

interface Fb { app: FirebaseApp; db: Firestore; storage: FirebaseStorage; ai: AI; user: User }
let ready: Promise<Fb> | null = null;
let fb: Fb | null = null;

/** Firebase for this browser: App Check (reCAPTCHA Enterprise), an anonymous user, Firestore with an offline cache. */
export function firebase(): Promise<Fb> {
  if (ready) return ready;
  ready = (async () => {
    const app = getApps()[0] ?? initializeApp(config);
    const siteKey = process.env.NEXT_PUBLIC_RECAPTCHA_SITE_KEY;
    if (siteKey) initializeAppCheck(app, { provider: new ReCaptchaEnterpriseProvider(siteKey), isTokenAutoRefreshEnabled: true });
    const db = initializeFirestore(app, { localCache: persistentLocalCache({ tabManager: persistentMultipleTabManager() }) });
    const storage = getStorage(app);
    const ai = getAI(app, { backend: new GoogleAIBackend() });
    const auth = getAuth(app);
    const user = await new Promise<User>((resolve, reject) => {
      const off = onAuthStateChanged(auth, (u) => {
        if (u) { off(); resolve(u); }
      });
      if (!auth.currentUser) signInAnonymously(auth).catch((e) => { off(); reject(e); });
    });
    fb = { app, db, storage, ai, user };
    return fb;
  })();
  ready.catch(() => { ready = null; });
  return ready;
}

/** Firebase once it's ready (after the loading screen). */
export function fbNow(): Fb {
  if (!fb) throw new Error("Firebase is not ready yet");
  return fb;
}
