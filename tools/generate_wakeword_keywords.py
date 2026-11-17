#!/usr/bin/env python3
"""Generate Sherpa-ONNX BPE keyword tokens for FRIDAY from the KWS model's own tokenizer.

Usage:
  python tools/generate_wakeword_keywords.py /path/to/bpe.model app/src/main/assets/friday_keywords.txt
"""
from __future__ import annotations

import argparse
from pathlib import Path
import sentencepiece as spm


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("bpe_model", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--phrase", default="FRIDAY")
    args = parser.parse_args()

    if not args.bpe_model.is_file():
        raise SystemExit(f"Missing SentencePiece model: {args.bpe_model}")

    tokenizer = spm.SentencePieceProcessor(model_file=str(args.bpe_model))
    pieces = tokenizer.encode(args.phrase.strip().upper(), out_type=str)
    if not pieces:
        raise SystemExit("The wake phrase produced no BPE tokens.")

    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(" ".join(pieces) + "\n", encoding="utf-8")
    print(f"Generated wake phrase {args.phrase!r}: {' '.join(pieces)}")


if __name__ == "__main__":
    main()
