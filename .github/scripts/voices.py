# 每個聲音念一句,估算平均音高(F0)分男女聲,順便量合成速度
import sys, time, numpy as np, sherpa_onnx
d = sys.argv[1]
cfg = sherpa_onnx.OfflineTtsConfig(
    model=sherpa_onnx.OfflineTtsModelConfig(
        kokoro=sherpa_onnx.OfflineTtsKokoroModelConfig(
            model=f"{d}/model.int8.onnx", voices=f"{d}/voices.bin", tokens=f"{d}/tokens.txt",
            data_dir=f"{d}/espeak-ng-data", dict_dir=f"{d}/dict",
            lexicon=f"{d}/lexicon-us-en.txt,{d}/lexicon-zh.txt"),
        num_threads=2),
    rule_fsts=f"{d}/date-zh.fst,{d}/phone-zh.fst,{d}/number-zh.fst",
    max_num_sentences=1)
tts = sherpa_onnx.OfflineTts(cfg)
n = tts.num_speakers
text = "Sir,十分鐘後開會,地點在三樓會議室。"
def f0(x, sr):
    vals = []
    hop = int(sr * 0.02); win = int(sr * 0.04)
    for i in range(0, len(x) - win, hop):
        w = x[i:i + win]
        if np.sqrt(np.mean(w ** 2)) < 0.02: continue
        w = w - w.mean()
        ac = np.correlate(w, w, "full")[win - 1:]
        lo, hi = int(sr / 400), int(sr / 70)
        k = lo + int(np.argmax(ac[lo:hi]))
        if ac[k] > 0.3 * ac[0]: vals.append(sr / k)
    return float(np.median(vals)) if vals else 0.0
out = []
for sid in range(n):
    t = time.time()
    a = tts.generate(text, sid=sid, speed=1.0)
    x = np.array(a.samples, dtype=np.float32)
    dt = time.time() - t
    p = f0(x, a.sample_rate)
    out.append((sid, p, len(x) / a.sample_rate, dt))
female = [s for s, p, _, _ in out if p >= 165]
male = [s for s, p, _, _ in out if 0 < p < 165]
print("speakers", n, "sr", tts.sample_rate)
print("female", female)
print("male", male)
print("detail", " ".join(f"{s}:{int(p)}" for s, p, _, _ in out))
print("avg audio %.2fs, avg synth %.2fs" % (np.mean([o[2] for o in out]), np.mean([o[3] for o in out])))
