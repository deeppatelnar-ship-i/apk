# Offline TouchFree Voice AI (Android)

100% offline voice assistant (Vosk speech-to-text + Android Accessibility Service). No INTERNET permission is declared.

## Get the APK (free, no installs on your PC)
1. Create a free GitHub account and a new **private** repository.
2. Upload all files of this project (drag & drop the unzipped folder contents in the browser, including the hidden `.github` folder).
3. Open the **Actions** tab -> **Build APK** -> wait ~6-10 minutes.
4. Open the finished run, download the artifact **OfflineTouchFreeAI-apk**, unzip it -> `app-debug.apk`.

The workflow downloads the Vosk model `vosk-model-small-en-in-0.4` and bundles it into the APK automatically.

## Build locally instead (Android Studio)
1. Download the model from https://alphacephei.com/vosk/models and copy the model's *contents* into `app/src/main/assets/model-en/`.
2. Open this folder in Android Studio, then Build > Build APK(s). Output: `app/build/outputs/apk/debug/app-debug.apk`.

## Install on the phone
1. Copy the APK to the phone and open it (allow "Install unknown apps" for your file manager/browser).
2. Open **Offline TouchFree AI**, grant Microphone / Phone / Contacts.
3. Tap **Open Accessibility Settings** -> Installed apps -> **TouchFree Offline Voice AI** -> turn on.
   On Samsung One UI, if the toggle is greyed out: Settings > Apps > Offline TouchFree AI > ⋮ > **Allow restricted settings**, then retry.
4. Say "Jarvis open WhatsApp", "Jarvis scroll down", "Jarvis call Mom" ...

## Optional on-device LLM
Put a MediaPipe `gemma-2b-it-cpu-int4.bin` into `Android/data/com.offlineai.touchfree/files/llm/` for free-form commands.
