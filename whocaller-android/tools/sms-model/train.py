"""Trains WhoCaller's on-device SMS spam model (logistic regression over word presence).

    pip install scikit-learn
    python train.py spam.csv ../../core/domain/src/main/resources/whocaller/sms_spam_model.txt

Input: the SMS Spam Collection CSV (columns v1=ham/spam, v2=text), or any CSV with the same layout,
e.g. your own Indian messages added at the end. Keep the preprocessing identical to SpamTextModel.kt.
"""
import csv, re, random, sys
from sklearn.feature_extraction.text import CountVectorizer
from sklearn.linear_model import LogisticRegression

URL = re.compile(r"(https?://\S+|www\.\S+|\b[a-z0-9-]+\.(?:ly|gl|link|xyz|top|click|info|online|site|in|com|co|uk|me|io)(?:/\S*)?)")

def norm(t):
    t = t.lower(); t = URL.sub(" zzurl ", t)
    t = re.sub(r"[£$€₹]|\brs\.?\s?(?=\d)|\binr\b", " zzmoney ", t)
    t = re.sub(r"\d{5,}", " zzlongnum ", t); t = re.sub(r"\d+", " zznum ", t)
    return t

def load(path):
    rows = []
    with open(path, encoding="latin-1") as f:
        r = csv.reader(f); next(r)
        for row in r:
            rows.append((1 if row[0].strip().lower() == "spam" else 0, ",".join(x for x in row[1:] if x)))
    return rows

def vectorizer():
    return CountVectorizer(preprocessor=norm, token_pattern=r"[a-z]{2,}", binary=True, min_df=3, max_features=1000)

def main(src, out):
    rows = load(src)
    # Held-out check first, then train on everything.
    random.seed(42); shuffled = rows[:]; random.shuffle(shuffled)
    k = int(len(shuffled) * 0.8); tr, te = shuffled[:k], shuffled[k:]
    v = vectorizer(); m = LogisticRegression(C=1, max_iter=3000).fit(v.fit_transform([t for _, t in tr]), [y for y, _ in tr])
    p = m.predict_proba(v.transform([t for _, t in te]))[:, 1]
    spam = [pp for pp, (y, _) in zip(p, te) if y]; ham = [pp for pp, (y, _) in zip(p, te) if not y]
    print(f"held-out @0.7: caught {sum(x >= 0.7 for x in spam) / len(spam):.1%} of spam, "
          f"flagged {sum(x >= 0.7 for x in ham) / len(ham):.2%} of normal messages")
    v = vectorizer(); m = LogisticRegression(C=1, max_iter=3000).fit(v.fit_transform([t for _, t in rows]), [y for y, _ in rows])
    with open(out, "w") as f:
        f.write("# WhoCaller SMS spam model v1: logistic regression over word presence.\n")
        f.write("# Trained on the SMS Spam Collection (Almeida & Gomez Hidalgo, UCI, CC BY 4.0).\n")
        f.write(f"bias {round(float(m.intercept_[0]), 4)}\n")
        for w, c in sorted(zip(v.get_feature_names_out(), m.coef_[0])):
            if abs(c) >= 0.01:
                f.write(f"{w} {round(float(c), 4)}\n")
    print("wrote", out)

if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
