# UhuruMax

Livestream app for live TV, football and movies, with an admin app and API.

| Folder | What it is |
| --- | --- |
| `/` (root) | **UhuruMax** user app (Flutter). Swahili UI, SonicPesa checkout, native Android player (HLS/DASH, ClearKey, Widevine, Referer/Origin headers). |
| `uhuru/` | **UhuruMax Admin** app (Flutter). Password-only login (24-hour sessions): channels, carousel, packages, users, payments, notifications, maintenance, must-update, refresh apps, danger zone. |
| `backend/` | API (Node.js, Express, Postgres). Deploys to Railway. SonicPesa payments and Firebase push. |

## Run locally

```bash
# API with an embedded Postgres (admin password: uhuru-admin)
cd backend && npm install && npm run dev:local

# User app (debug builds talk to http://localhost:8080, or 10.0.2.2 on the Android emulator)
flutter run

# Admin app
cd uhuru && flutter run
```

## Production

- **API:** set the variables from `backend/.env.example` on Railway (`DATABASE_URL`, `JWT_SECRET`, `ADMIN_PASSWORD`, SonicPesa keys, `FCM_SERVICE_ACCOUNT_JSON`).
- **Apps:** build with `--dart-define=API_URL=https://<your-api>.up.railway.app`.
- **Push (user app):** also pass `FIREBASE_API_KEY`, `FIREBASE_APP_ID`, `FIREBASE_SENDER_ID` and `FIREBASE_PROJECT_ID` as `--dart-define`s.
