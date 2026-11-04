# SONIC REMOTE — Android starter

This is a **starter scaffold**, not a finished remote-control product.

## Included
- One Android app with separate Controller / Agent entry points.
- An explicit consent screen for the Agent side.
- A visible session status and a Stop button.
- Supabase configuration placeholders.
- The existing Supabase schema is **not recreated or modified** by this package.

## Important safety design
- No hidden operation, persistence, stealth, lock-screen bypass, or silent screen capture.
- Screen sharing must use Android's system MediaProjection consent prompt.
- The Agent user must be able to stop a session at any time.
- Do not add remote input/accessibility control until authorization and security review are complete.

## Open in Android Studio
1. Extract this ZIP.
2. Open the `SONIC-REMOTE` folder in Android Studio.
3. Let Gradle sync.
4. Run on an Android device.

## Supabase setup
Open `app/build.gradle.kts` and replace:
- `SUPABASE_URL`
- `SUPABASE_PUBLISHABLE_KEY`

Use the project's URL and **Publishable key** only. Never put a `service_role` or secret key in an Android app or GitHub repository.

The app currently displays the consent flow locally. Backend authentication, device registration, secure pairing, signaling, and WebRTC are not implemented yet.

## Upload to your private GitHub repository
Extract the ZIP first. Upload the **contents** of this folder to the root of your private repository:
https://github.com/yanihamri2008-cmd/sonic-remote

Do not upload the ZIP as the only repository file if you want GitHub Actions/Android Studio to see the project structure.
