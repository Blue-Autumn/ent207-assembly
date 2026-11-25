# Assembly Assistant

[**Try Assembly Assistant in your browser**](https://blue-autumn.github.io/ent207-assembly/) · [Open the virtual workshop](https://blue-autumn.github.io/ent207-assembly/workshop/) · [Download the course APK](https://pan.quark.cn/s/fd6e4a6732be?pwd=tfWZ&entry=webother)

Assembly Assistant helps a first-time builder put together a five-tier shelf. Rehearse a step on the 3D model, see which pieces to pick up, then use the camera to identify the tagged boards. The Android app is the full project. The browser edition lets visitors try the workflow without installing anything.

## A two-minute tour

1. Open the browser demo and choose English or Chinese. You can skip camera access for this tour.
2. Choose **Virtual assembly**. Rotate the shelf, open the parts view, then play a step to see how the two halves meet. Rehearsal is separate from saved physical progress.
3. Return home and open **Guided assembly** to see the part finder and placement instructions. The welcome screen also links to the course video, vote and Android download.
4. On Android, open **Five-tier shelf** from the home screen and choose **Assembly steps** for rehearsal. You start as a guest. Guest progress stays on the phone. Optional local accounts keep separate progress on that device.

## Use the physical kit

The course kit has 16 pieces and 128 registered ArUco labels from the 4×4/250 dictionary. On a fresh Android install, download the [five-tier tag map](https://blue-autumn.github.io/ent207-assembly/android/examples/five-tier-tag-map.json) to the phone. In the guided assembly settings choose **Import tag map**, select the JSON and confirm the summary of 16 parts and 128 markers. For live guidance, allow camera access and show the labels on the requested piece. Work through the preparation and installation steps and confirm each action yourself.

The map and board outlines match this particular kit. Lighting, focus, occlusion and incorrect sticker positions affect detection. A visible tag identifies a part. The camera cannot certify that a joint is fully seated or that the shelf is structurally sound. Without the matching boards, use the virtual workshop or the web photo examples.

## Install Android 0.5.47 (R91)

The course download above uses extraction code **tfWZ**. Download the APK to an Android 8.0+ device and open it with the system package installer, or run `adb install Assembly-Assistant-0.5.47.apk`. Its SHA-256 is:

```text
2a0495e1e91ffce0b7a5703afd85a3f6d5464453b716924927c7396517a94e86
```

The course APK and locally built debug APKs have different signatures. Use a separate device or emulator if you already have a differently signed installation with progress you want to keep. The original course release was smoke-tested on a fresh emulator. The same version was demonstrated with a development signature on a Huawei handset.

## Build from source

Open [android](android/) in Android Studio, or use its Gradle wrapper. Install Android SDK Platform 36, Build Tools 36.0.0, a JDK compatible with Gradle 9.1 (JDK 17 or 21), and Python 3 on your PATH for the bilingual resource checks. Set `ANDROID_HOME` to your SDK or let Android Studio create local.properties. The first build downloads dependencies from Google, Maven Central and Huawei.

```sh
cd android
./gradlew clean testDebugUnitTest assembleDebug
```

On Windows, run `.\gradlew.bat clean testDebugUnitTest assembleDebug`. Install the resulting `app/build/outputs/apk/debug/app-debug.apk` or use Android Studio's Run button. The `assembleRelease` task builds an unsigned release for maintainers to sign using their own credentials.

## Online features

The virtual workshop and Android marker guidance work locally. Android photo review uses a DeepSeek key supplied in the app's AI settings and stored on the device. Submitting a review sends the selected photos and assembly context to the provider. The web edition sends optional AI requests through a separate Cloudflare service. The browser downloads its camera detector and processes live frames locally. Online review, design suggestions and generated replies depend on service availability. The configured web allowance is 300 shared calls per Beijing day from 16 January through 30 June 2027, with the service scheduled to close on 1 July.

## Developer guide

| Area | Where to start |
| --- | --- |
| Website | [index.html](index.html), [app.js](app.js) and [welcome.js](welcome.js) handle entry and navigation. [workshop](workshop/) contains virtual assembly, [guide](guide/) the physical steps and [vision](vision/) the browser detector and guidance runtime. |
| Android | [Kotlin sources](android/app/src/main/java/com/example/arassemblydemo/) include MainActivity, AssemblyGuideActivity, VirtualAssemblyActivity and the local recognition and account code. [Assets](android/app/src/main/assets/) contain the shared geometry, bilingual guides and offline rehearsal UI. Edit the packaged assets directly. [Unit tests](android/app/src/test/) run without course evidence files. |
| Browser vision build | [Development sources](development/vision/) include the Kotlin/JS bridge, geometry packager and SIMD preprocessing source. The checked-in browser runtime is ready to serve. |
| Optional web AI | [backend](backend/) contains the worker, reference data, quota schema and offline checks. Use Node 24 and run `node backend/qa/check.mjs` from the repository root. For a separate deployment, copy the example config to wrangler.jsonc, supply your own D1 database bound as LIMITS, apply schema.sql and set DEEPSEEK_API_KEY as a server secret. Set the frontend endpoint in services and the allowed origin in the worker for your deployment. |
| Dependencies and assets | [Third-party notices](THIRD_PARTY_NOTICES.md) list licenses and vendor-specific integration terms. |

Preview the website from the repository root with `python -m http.server 8000` and visit `http://localhost:8000/`. Use localhost or HTTPS for camera access. Mobile browser camera selection, focus and performance vary by device. Native AR Engine experiments also require compatible Huawei hardware and services.

**Publishing.** GitHub Pages serves the static files at the repository root on main. Keep the current directory layout to preserve the course QR code and deep links. For updates, start from current main, commit only reviewed site changes and push normally. A website export must preserve android/, backend/, development/ and the root documentation. Keep signing files, local credentials, device data and build output outside Git.
