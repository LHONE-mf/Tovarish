# Tovarish

Local Android AI chat for OnePlus 15 (Snapdragon 8 Elite Gen 5 / SM8850, 12 GB RAM).

## Milestone 1
Build a minimally renamed Qualcomm GenieX Chat reference app with its original model catalog and inference behavior. Validate a standard Qwen model on the phone before adding features. No model weights are stored in this repository.

Upstream: https://github.com/qualcomm/ai-hub-apps (BSD-3-Clause sample; SDK has separate license). Baseline commit: `e6477cfa238acaffe01ed45de4cfcbd53abbc99d`.

Future work: measure latency, tokens/s, memory, temperature and battery energy; compare supported backends using identical workloads. Keep chat local after model download. No automatic release or publishing.
