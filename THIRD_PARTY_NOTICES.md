# Third-Party Notices

This Android fork redistributes or bundles the following third-party components directly:

1. `Supertonic` source code by Supertone Inc.
   License: MIT
   Source: https://github.com/supertone-inc/supertonic

2. `Supertonic 2` model assets by Supertone Inc.
   License: BigScience Open RAIL-M
   Source: https://huggingface.co/Supertone/supertonic-2

3. `ONNX Runtime Android` by Microsoft Corporation
   License: MIT
   Source: https://github.com/microsoft/onnxruntime

4. `ort` Rust crate by pyke.io / Nicolas Bigaouette
   License: MIT OR Apache-2.0
   Source: https://github.com/pykeio/ort

This fork contains local modifications for Android packaging, JNI build integration, TTS engine behavior, and update workflow tooling. Those modifications are distributed as part of this repository under the same repository terms that apply to this forked source tree.

The full bundled notice documents shipped in the app are:

- `THIRD_PARTY_NOTICES.md`
- `files/SUPERTONIC_SOURCE_CODE_LICENSE.txt`
- `files/SUPERTONIC_2_MODEL_LICENSE.txt`
- `files/ONNXRUNTIME_LICENSE.txt`
- `files/ORT_CRATE_LICENSE_MIT.txt`
- `files/ORT_CRATE_LICENSE_APACHE_2.0.txt`

For the model assets, the Open RAIL-M use restrictions continue to apply to downstream redistribution and use.
