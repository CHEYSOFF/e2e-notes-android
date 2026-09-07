# Mañana

Mañana is a notes app built on the idea that a note you write down should not become somebody
else's data. Notes live in an encrypted database on your phone, opened with a PIN or your
fingerprint, and they sync to a desktop app and between your own devices through a server you run
yourself. That server only ever holds sealed blobs and a counter that goes up — it never learns a
title, a word of a note, or even what kind of thing you saved. The keys are generated on your first
device and reach a second one by holding the two screens up to each other and scanning a pair of QR
codes; they are never uploaded, and there is no account to sign in to.

Day to day it behaves like an ordinary notes app. Folders, pinning, favourites, checklists, search,
and a trash that gives you thirty days to change your mind. There is a calendar view showing when
you actually worked on things, you can sketch on a note with a proper colour picker, and you can
attach photos — both of which sync as first-class records rather than as files on the side. A
Compose Desktop build for Windows and macOS talks to the same server and shows the same notes, and
the two stay in step through per-field clocks, so pinning something on your phone while editing it
on your laptop merges instead of one side winning.

The trade-offs are deliberate, and worth knowing before you trust it with anything that matters.
There is no account recovery, because there is nobody to recover it from: forget your PIN with only
one device set up and the notes are gone. A second paired device is the only backup that exists. In
exchange, nothing sits on someone else's server waiting to be breached, subpoenaed or quietly mined,
and the app asks for exactly two permissions — the camera, to scan a pairing code, and the internet,
to reach the one address you pointed it at.
