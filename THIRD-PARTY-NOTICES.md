# Third-party notices

Wardrobapp is AGPL-3.0 (see [LICENSE](LICENSE)); the pieces below are other
people's, under their own licences, and are named here because those licences
ask for it or because somebody wondering where a thing came from should be able
to find out. The build files (`*/build.gradle.kts`) list every library the apps
are built with; this file names the ones that are copied into the repository or
into what is shipped, rather than merely linked.

## Material Icons

The glyphs under `art/glyphs/*.svg`, from which `scripts/generate-glyphs.py`
writes `ui/.../GlyphVectors.kt`, are from [Material Icons](https://fonts.google.com/icons)
by Google, under the [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0).
Each SVG names its source in a comment. They are copied rather than depended on
because the library that carries them carries every icon Google has drawn.

## silueta (U²-Net)

The background-removal model the Home Assistant app runs, `silueta.onnx`, is a
smaller U²-Net published by the [rembg](https://github.com/danielgatis/rembg)
project, under the [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0);
U²-Net itself is by Xuebin Qin et al. The model is downloaded when the server is
built (see `server/build.gradle.kts`, which checks its hash) and is in every
published image at `/opt/wardrobapp/models/silueta.onnx`. It is run with
[ONNX Runtime](https://github.com/microsoft/onnxruntime), MIT License,
Copyright (c) Microsoft Corporation.

## CLIP (ViT-B/32)

The style model the Home Assistant app runs, `style-image-vitb32-int8.onnx`, is
the image encoder of OpenAI's [CLIP](https://github.com/openai/CLIP) ViT-B/32,
MIT License, Copyright (c) 2021 OpenAI, converted to ONNX and quantised to 8-bit
weights with [OpenCLIP](https://github.com/mlfoundations/open_clip), MIT License,
Copyright (c) 2012-2021 Gabriel Ilharco et al. `style-anchors.json` holds
embeddings of short English phrases made with the same model's text encoder.
Both are made by `scripts/export-style-model.py`, published as a release of
this repository, downloaded when the server is built (see
`server/build.gradle.kts`, which checks their hashes), and are in every
published image under `/opt/wardrobapp/models/`. The model is run with ONNX
Runtime, as above.

## QR Code generator

The QR code encoder in `presentation/.../QrCode.kt` follows
[Project Nayuki's QR Code generator library](https://www.nayuki.io/page/qr-code-generator-library),
which is the clearest reading of ISO/IEC 18004 there is. It is a reimplementation
in this project's style rather than a copy of the library, but it is its
algorithm, and the library's licence is reproduced here as it asks:

> Copyright © Project Nayuki. (MIT License)
> https://www.nayuki.io/page/qr-code-generator-library
>
> Permission is hereby granted, free of charge, to any person obtaining a copy of
> this software and associated documentation files (the "Software"), to deal in
> the Software without restriction, including without limitation the rights to
> use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
> the Software, and to permit persons to whom the Software is furnished to do so,
> subject to the following conditions:
>
> - The above copyright notice and this permission notice shall be included in
>   all copies or substantial portions of the Software.
> - The Software is provided "as is", without warranty of any kind, express or
>   implied, including but not limited to the warranties of merchantability,
>   fitness for a particular purpose and noninfringement. In no event shall the
>   authors or copyright holders be liable for any claim, damages or other
>   liability, whether in an action of contract, tort or otherwise, arising from,
>   out of or in connection with the Software or the use or other dealings in the
>   Software.

## Libraries the Android app links

Not copied, but worth naming because they are visible in what the app does:

- [AppAuth for Android](https://github.com/openid/AppAuth-Android), Apache
  License 2.0, which shows the Google sign-in for Drive backups in a browser
  tab rather than a WebView.
- [Android Image Cropper](https://github.com/CanHub/Android-Image-Cropper),
  Apache License 2.0, the crop screen.
- Google Play services: ML Kit subject segmentation, which removes a photo's
  background on the phone, and the code scanner, which reads the pairing QR
  code. These are Google's, under the
  [Google APIs Terms of Service](https://developers.google.com/terms), and are
  fetched by Play services rather than shipped in the APK; PRIVACY.md says
  what that means for the network.
- [Kotlin](https://kotlinlang.org), [Ktor](https://ktor.io),
  [Compose Multiplatform](https://www.jetbrains.com/compose-multiplatform/) and
  the AndroidX libraries, Apache License 2.0, which everything is written with.
