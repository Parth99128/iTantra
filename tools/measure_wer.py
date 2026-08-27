"""
Quick WER (Word Error Rate) test harness — one of the graded metrics (40% weight).

Usage:
    pip install jiwer --break-system-packages
    python measure_wer.py

How to use this for real:
1. Record yourself/teammates speaking ~20-30 sentences per demo language.
2. Run each recording through your Android app's STT (log the output, or add a
   temporary debug screen that prints the final transcript).
3. Fill in `ground_truth` and `stt_output` below with matching pairs.
4. Run this script — it prints per-sentence and overall WER so you know your real
   numbers BEFORE a judge asks, instead of guessing on stage.
"""

import jiwer

# Replace with your actual test sentences and what your app's STT actually returned.
ground_truth = [
    "madad chahiye yahan par baadh aa gayi hai",
    "sabhi log surakshit sthaan par chale jaayen",
    "yah ek pareekshan sandesh hai",
]

stt_output = [
    "madad chahiye yahan par bad aa gai hai",
    "sabhi log surakshit sthan par chale jayen",
    "yeh ek parikshan sandesh hai",
]

transformation = jiwer.Compose([
    jiwer.ToLowerCase(),
    jiwer.RemoveMultipleSpaces(),
    jiwer.Strip(),
    jiwer.ReduceToListOfListOfWords(),
])

overall_wer = jiwer.wer(
    ground_truth, stt_output,
    truth_transform=transformation, hypothesis_transform=transformation
)

print(f"Overall WER: {overall_wer:.2%}\n")
print("Per-sentence breakdown:")
for i, (gt, hyp) in enumerate(zip(ground_truth, stt_output)):
    sentence_wer = jiwer.wer([gt], [hyp], truth_transform=transformation, hypothesis_transform=transformation)
    print(f"  [{i+1}] WER={sentence_wer:.2%}")
    print(f"      truth: {gt}")
    print(f"      stt:   {hyp}")
