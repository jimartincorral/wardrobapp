#!/usr/bin/env python3
"""Export the style model the Home Assistant app runs, and its anchors.

The server embeds every garment photo, and every photo of a look the reader
likes, with the image half of CLIP: OpenAI's ViT-B/32, whose weights are MIT.
This writes that half as an ONNX file ONNX Runtime can run on a Pi, quantised
to 8-bit weights (a third of the size, within a hair of the same answers), and
a JSON of "anchors": the text half's embedding of a few sentences per
attribute value -- "a photo of formal evening wear", "a photo of a striped
garment" -- so the server can read formality, pattern, fit and weight off a
photo by nearest anchor without running the text model at all. The text model
needs a tokenizer the server does not have; a few hundred numbers in a JSON
file need nothing.

Run by .github/workflows/style-model.yml, once, which attaches both files to
a release of this repository; server/build.gradle.kts downloads them from
there by digest. Run it again only to change the model or the prompts, and
bump the release tag when you do: the digests are pinned.
"""

import hashlib
import json
import sys
from pathlib import Path

import numpy
import onnx
import open_clip
import torch
from onnxruntime.quantization import QuantType, quantize_dynamic

# The quickgelu variant, not plain "ViT-B-32": OpenAI trained these weights
# with the QuickGELU activation, and open_clip's plain config uses GELU. Loading
# the weights into the plain one runs, with a warning ("QuickGELU mismatch"),
# and gives embeddings a little off from the model's own -- and the smoke test
# below cannot see it, since it compares the export with the same mismatched
# model. The quickgelu config is the one the weights belong to.
MODEL = "ViT-B-32-quickgelu"
PRETRAINED = "openai"
MODEL_ID = "clip-vit-b32-openai"

# Named here rather than derived from MODEL, so the variant's suffix does not
# end up in a file name the server's build pins.
IMAGE_FILE = "style-image-vitb32-int8.onnx"

# Several sentences per value, averaged: CLIP's answer to one sentence is
# noisy, and the average of five phrasings is what the papers call a prompt
# ensemble. Written about garments and photos of garments, since that is
# what every embedding this is compared with is of.
ANCHORS = {
    "formality": {
        "lounge": ["loungewear", "sportswear", "pajamas", "a tracksuit", "gym clothes"],
        "casual": ["casual clothing", "a t-shirt", "jeans and a hoodie", "everyday streetwear", "a casual outfit"],
        "smart-casual": ["smart casual clothing", "a polo shirt and chinos", "a neat weekend outfit", "a casual shirt", "a cardigan over a shirt"],
        "smart": ["smart office clothing", "a blazer", "business attire", "a dress shirt and trousers", "a smart dress"],
        "formal": ["formal evening wear", "a tuxedo", "a cocktail dress", "black tie attire", "an elegant gown"],
    },
    "pattern": {
        "solid": ["a plain solid-coloured garment", "a garment in one plain colour", "a solid colour with no pattern"],
        "stripes": ["a striped garment", "stripes", "a pinstripe pattern"],
        "checks": ["a checked garment", "a plaid pattern", "gingham", "a tartan pattern"],
        "print": ["a printed garment", "a floral print", "a graphic print", "a patterned garment with a print"],
        "texture": ["a textured knit", "a cable knit", "a ribbed garment", "a woven texture"],
    },
    "fit": {
        "fitted": ["a fitted garment", "a slim fit", "a tight-fitting garment", "a tailored slim cut"],
        "regular": ["a regular fit garment", "a classic fit", "a straight cut"],
        "relaxed": ["a relaxed fit garment", "a loose fit", "a comfortable loose cut"],
        "oversized": ["an oversized garment", "a baggy garment", "an oversized boxy cut"],
    },
    "weight": {
        "light": ["a lightweight summer garment", "a thin light fabric", "a linen garment", "a light cotton garment"],
        "mid": ["a medium weight garment", "a denim garment", "a mid-weight fabric"],
        "heavy": ["a heavy winter garment", "a thick wool garment", "a padded coat", "a chunky knit"],
    },
    "statement": {
        "yes": ["a statement piece", "a bold eye-catching garment", "a striking standout garment"],
        "no": ["a basic wardrobe staple", "a plain everyday basic", "an understated simple garment"],
    },
}

TEMPLATES = ["a photo of {}.", "a product photo of {}.", "{}, on a plain background."]


class ImageTower(torch.nn.Module):
    """The image encoder alone, answering the raw embedding; the server normalises."""

    def __init__(self, clip):
        super().__init__()
        self.visual = clip.visual

    def forward(self, image):
        return self.visual(image)


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main(out: Path) -> None:
    out.mkdir(parents=True, exist_ok=True)
    clip, _, preprocess = open_clip.create_model_and_transforms(MODEL, pretrained=PRETRAINED)
    clip.eval()
    tokenizer = open_clip.get_tokenizer(MODEL)

    # The preprocessing the model was trained with, carried in the anchors file
    # so the server's resize and normalisation match it exactly.
    normalize = next(t for t in preprocess.transforms if t.__class__.__name__ == "Normalize")
    image_size = clip.visual.image_size
    if isinstance(image_size, (tuple, list)):
        image_size = image_size[0]

    full = out / "style-image-full.onnx"
    quantised = out / IMAGE_FILE
    # PyTorch's current exporter (dynamo=True, the default now, said so the
    # call reads the same on any version). Opset 18 because that is where it
    # starts, and asking for an older one is a conversion that can only fail;
    # ONNX Runtime 1.30, which the server runs, reads 18. A fixed batch of one,
    # since the server embeds one picture at a time and a dynamic axis is a
    # second thing to get right for nothing. external_data=False keeps the
    # weights inside the one file instead of a .data file beside it, which
    # the quantiser and the release would both have to know about.
    with torch.no_grad():
        torch.onnx.export(
            ImageTower(clip),
            (torch.zeros(1, 3, image_size, image_size),),
            str(full),
            input_names=["image"],
            output_names=["embedding"],
            opset_version=18,
            dynamo=True,
            external_data=False,
        )
    onnx.checker.check_model(str(full))
    quantize_dynamic(str(full), str(quantised), weight_type=QuantType.QUInt8)
    full.unlink()

    anchors = {}
    with torch.no_grad():
        for attribute, values in ANCHORS.items():
            anchors[attribute] = {}
            for value, phrases in values.items():
                sentences = [template.format(phrase) for phrase in phrases for template in TEMPLATES]
                text = clip.encode_text(tokenizer(sentences))
                text = text / text.norm(dim=-1, keepdim=True)
                mean = text.mean(dim=0)
                mean = mean / mean.norm()
                anchors[attribute][value] = [round(float(x), 6) for x in mean]

    dimensions = len(next(iter(next(iter(anchors.values())).values())))
    document = {
        "model": MODEL_ID,
        "imageSize": int(image_size),
        "mean": [float(x) for x in normalize.mean],
        "deviation": [float(x) for x in normalize.std],
        "dimensions": dimensions,
        "anchors": anchors,
    }
    anchors_file = out / "style-anchors.json"
    anchors_file.write_text(json.dumps(document, indent=1))

    # A smoke test on the exported model: the same picture through PyTorch and
    # through ONNX Runtime should agree, give or take the quantisation.
    import onnxruntime

    session = onnxruntime.InferenceSession(str(quantised), providers=["CPUExecutionProvider"])
    picture = torch.rand(1, 3, image_size, image_size)
    with torch.no_grad():
        expected = ImageTower(clip)(picture).numpy()
    actual = session.run(None, {"image": picture.numpy()})[0]
    cosine = float(numpy.dot(expected[0], actual[0]) / (numpy.linalg.norm(expected[0]) * numpy.linalg.norm(actual[0])))
    print(f"ONNX agrees with PyTorch to cosine {cosine:.4f}")
    # A broken export lands near zero; an 8-bit one on random noise, which is
    # nothing like a photo, can wander a few hundredths from one. 0.95 tells
    # the two apart without failing a sound export on a picture of static.
    if cosine < 0.95:
        sys.exit("the quantised export disagrees with the model")

    for path in (quantised, anchors_file):
        print(f"{digest(path)}  {path.name}  ({path.stat().st_size // 1024} KB)")


if __name__ == "__main__":
    main(Path(sys.argv[1] if len(sys.argv) > 1 else "style-model"))
