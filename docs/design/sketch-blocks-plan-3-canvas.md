# Sketches: the canvas — implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** You can draw a scribble on your phone with a finger, it appears in the note, and it syncs to the desktop.

**Architecture:** A full-screen Compose canvas captures finger strokes into the integer canvas space the codec already defines, simplifies them, and saves a `SketchData` through the repository seam. The note editor renders a note's sketches below its text, in the position the checklist block already occupies. The desktop renders them and cannot draw.

**Tech Stack:** Compose (Android + Desktop), Kotlin Multiplatform for the shared geometry, Room, JUnit 4.

**Spec:** [`sketch-blocks.md`](sketch-blocks.md) — the "Drawing UX" and "Rendering elsewhere" sections, **plus its 2026-09-05 amendment**, which defers inline placement and is not optional reading: it explains why sketches render below the text rather than between paragraphs.

**One obligation inherited from plan 2 is deliberately dropped:** re-stamping a sketch's `anchor` when text *above* it is edited. That existed to keep an inline drawing beside the right paragraph, and the amendment defers inline rendering — sketches render below the text, where no amount of editing above them moves anything. The anchor is still written at creation, so the data stays correct for whenever inline rendering arrives; there is simply nothing to maintain until then. Re-stamping now would dirty a sketch on every text edit for no visible benefit.

**Prerequisite:** PR #102 (the data path) is on master. `RecordType.SKETCH`, `StrokeCodec`, `NoteBlocks`, the `sketches` table, the repository seam and two-device sync all exist and are tested.

## Global Constraints

- **`Color.toArgb()` returns a NEGATIVE Int for any alpha ≥ 0x80.** `StrokeCodec.encode` masks with `and 0xFFFFFFFFL` for exactly this reason. Pass colours as `color.toArgb().toLong()` and let the codec mask; do not pre-normalise, and do not remove the mask.
- **`SketchData`'s `createdAt`/`updatedAt` are caller-owned**, unlike `Note` whose repository stamps `updatedAt` itself. Every save from the canvas must set `updatedAt`, or the drawing sorts and merges by a stale time.
- **`NoteBlocks` counts a whitespace-only line as a block.** If any rendering code counts blocks, it must agree with `NoteBlocks` or a drawing sits a line off.
- **No floats in anything that reaches `StrokeCodec`.** Screen coordinates are floats; canvas coordinates are integers. Convert once, at capture.
- `./gradlew verify` is the gate. Run it in the **foreground** with `ANDROID_SERIAL=46281FDAS005MQ` — the `migration_test` AVD has failed to attach to instrumentation repeatedly, and Gradle counts a device that ran zero tests as a failure.
- A `--tests` filter that matches nothing prints no failures and looks like a pass. Confirm the test name appears, and run full module suites.
- **Kotlin/Native rejects commas inside backtick test-function names.**
- `:core-domain` `commonTest` uses `kotlin.test` (message **last**); `:core-data`, `:feature-notes` and `:core-sync-codec` use JUnit (message **first**).
- Commit messages: descriptive prose, no AI attribution, no `Co-Authored-By`.

---

## File Structure

**Create:**

| File | Responsibility |
|---|---|
| `core-domain/src/commonMain/.../sketch/StrokeSimplifier.kt` | Ramer–Douglas–Peucker over integer points. Pure, shared, testable. |
| `core-domain/src/commonMain/.../sketch/SketchLimits.kt` | The size guard, and the one place its numbers live. |
| `core-ui/src/main/.../sketch/SketchRenderer.kt` | Turn a `Sketch` into a Compose `Path`. Shared by the canvas, the note block, and the desktop. |
| `feature-notes/src/main/.../ui/sketch/SketchCanvasScreen.kt` | The full-screen drawing surface and its tool bar. |
| `feature-notes/src/main/.../ui/sketch/SketchCaptureState.kt` | Stroke capture, undo/redo, erase — no Compose drawing, so it is unit-testable. |

**Modify:** `SingleNoteScreen.kt` (a sketch block below the text), `SingleNoteViewModel.kt`, `RoomSyncStore.kt` (Task 1), the desktop's note pane.

---

### Task 1: Reconcile a live sketch when its note's tombstone arrives

**This blocks everything else in this plan** and is not UI work. It is the follow-up PR #102 named as a hard blocker.

**Files:**
- Modify: `core-data/.../data/sync/RoomSyncStore.kt`
- Test: `core-data/src/test/java/my/cheysoff/core_data/SketchDeletionTest.kt`

**The hole.** `reconcileAgainstNote` runs only when a **sketch** record arrives. So when a *note tombstone* arrives for a note whose sketch this device already holds **live**, nothing re-examines the sketch. The desktop never cascades — it has no sketch-aware delete path — so it is a **permanently** sketch-unaware deleter: every note deleted on the desktop leaves a live orphan sketch on the phone, never tombstoned, never reaped by `purgeExpiredTrash`.

**The fix.** When a merged note write results in a note that is deleted, tombstone that note's still-live sketches, in the same transaction, each with its own clock bump so it propagates.

Mind two things:
- **Only when the note *becomes* deleted**, not on every merged note write. Tombstoning on every write would re-stamp sketches endlessly and make them permanently dirty.
- **This must not fight `restoreNote`.** Restore un-tombstones sketches whose `deletedAt >= the note's`; if reconciliation stamps a *later* `deletedAt` than the note carries, restore will still catch it — confirm that, and if it does not hold, say so rather than working around it.

- [ ] **Step 1: Write the failing tests**

```kotlin
    /**
     * The desktop has no sketch-aware delete path, so it is a permanently sketch-unaware deleter:
     * it tombstones a note and nothing else. Without this, every note deleted there leaves a live
     * orphan sketch on the phone — never shown, never tombstoned, and therefore never reaped by the
     * trash purge.
     */
    @Test
    fun aNoteTombstoneArrivingTombstonesTheLiveSketchesThisDeviceAlreadyHolds() { /* full body */ }

    /** Re-stamping on every merged note write would keep every sketch permanently dirty. */
    @Test
    fun aMergedNoteWriteThatDoesNotDeleteTheNoteLeavesItsSketchesAlone() { /* full body */ }

    /** The sketches this reconciliation tombstones must still come back when the note is restored. */
    @Test
    fun sketchesTombstonedByAnArrivingNoteTombstoneAreRestoredWithTheNote() { /* full body */ }
```

Write full bodies. Seed the "already holds live" state through the real store, not by hand-editing rows, or the test proves nothing about the path that matters.

- [ ] **Step 2: Run them and watch them fail**

Run: `./gradlew :core-data:testDebugUnitTest --tests "my.cheysoff.core_data.SketchDeletionTest"`

- [ ] **Step 3: Implement**, in `applyMerged`'s NOTE branch.
- [ ] **Step 4: Run the full `:core-data:testDebugUnitTest` and quote the total.**
- [ ] **Step 5: Falsify** — make the trigger fire on every note write and watch the second test fail; remove the clock bump and watch a propagation assertion fail. Restore, `git diff` clean. Report what you saw.
- [ ] **Step 6: Commit**

```bash
git commit -m "Tombstone a note's live sketches when its tombstone arrives"
```

---

### Task 2: Simplification and the size guard

Pure, shared, and needed before the canvas can save anything.

**Files:**
- Create: `core-domain/src/commonMain/kotlin/my/cheysoff/core_domain/sketch/StrokeSimplifier.kt`
- Create: `core-domain/src/commonMain/kotlin/my/cheysoff/core_domain/sketch/SketchLimits.kt`
- Test: `core-domain/src/commonTest/kotlin/my/cheysoff/core_domain/sketch/StrokeSimplifierTest.kt`, `SketchLimitsTest.kt`

**Interfaces:**
- Produces: `StrokeSimplifier.simplify(points: List<Point>, epsilon: Int): List<Point>`; `SketchLimits.MAX_ENCODED_BYTES`, `SketchLimits.withinLimit(encoded: String): Boolean`.

Ramer–Douglas–Peucker, in integers. A finger produces a point per touch event — hundreds per stroke — and storing them raw makes a scribble ten times larger than it needs to be. **Endpoints are always kept.** Perpendicular distance must be computed without floating point: compare squared distances scaled by the segment length, or use the integer cross-product form. If you cannot avoid a float internally, say so in your report — the *output* must be integers regardless, and that is the property tested.

The guard exists because the spec asks to refuse an oversized stroke set **at capture time**, "rather than discovering it at push time, where the failure is a `413` the user cannot act on". A sealed envelope caps at 256 KiB (`ServerConfig.maxEnvelopeBytes`); pick a limit well under it and state the reasoning where the constant lives.

- [ ] **Step 1: Write the failing tests** — a straight line of many points simplifies to its two endpoints; a right-angle keeps its corner; endpoints are never dropped; simplification is deterministic for a given input and epsilon; the output contains no value the input could not produce; `withinLimit` accepts a typical scribble and refuses one past the cap.
- [ ] **Step 2–4: fail, implement, pass on `./gradlew :core-domain:allTests`**, confirming the mingwX64 results name the new tests.
- [ ] **Step 5: Commit**

---

### Task 3: Stroke capture, undo and erase — without Compose

**Files:**
- Create: `feature-notes/src/main/java/my/cheysoff/feature_notes/ui/sketch/SketchCaptureState.kt`
- Test: `feature-notes/src/test/java/my/cheysoff/feature_notes/SketchCaptureStateTest.kt`

**Interfaces:**
- Consumes: `StrokeSimplifier.simplify` and `SketchLimits` (Task 2); `Sketch`/`Stroke`/`Point` (PR #102).
- Produces: a holder **constructed with the canvas dimensions** (`SketchCaptureState(width: Int, height: Int)` — it cannot produce a `Sketch` without them, and they must be the same integers the codec stores), exposing `beginStroke(x, y)`, `extendStroke(x, y)`, `endStroke()`, `undo()`, `redo()`, `eraseAt(x, y)`, `toSketch(): Sketch`, and a `strokes` snapshot for rendering.

**Simplify at `endStroke`, not at `toSketch`.** Three reasons, and they are the difference between this working and merely appearing to: undo then operates on the same strokes that were stored, so undoing does not silently change the drawing; the size guard sees the real stored size rather than the raw capture; and rendering during the session matches rendering after a reload, so a drawing does not visibly shift the moment it is saved.

Deliberately **no Compose types** — it takes plain numbers so it can be unit-tested without a device. That is the difference between testing the drawing logic and eyeballing it.

Rules worth pinning in tests:
- Undo removes the last completed stroke; redo puts it back; a new stroke after an undo clears the redo stack (the universal convention — anything else surprises people).
- Erase is **stroke-level**: touching within a tolerance of a stroke removes that whole stroke. Pixel erasing with a fingertip is imprecise, and for a scribble the unit someone means to remove is the whole mark.
- An erase is undoable, like any other operation. A drawing tool where undo cannot recover an accidental erase is a tool that loses work.
- Coordinates arriving outside the canvas are clamped, not dropped — a finger sliding off the edge should end the stroke at the edge, not truncate it mid-air.

- [ ] **Step 1–5: tests first, implement, full `:feature-notes:testDebugUnitTest`, commit.**

---

### Task 4: The canvas screen

**Files:**
- Create: `core-ui/src/main/java/my/cheysoff/core_ui/sketch/SketchRenderer.kt`
- Create: `feature-notes/src/main/java/my/cheysoff/feature_notes/ui/sketch/SketchCanvasScreen.kt`

**Interfaces:**
- Produces: `sketchPath(sketch: Sketch, size: Size): Path` (or per-stroke equivalent) in `core-ui`, used by the canvas, the note block and the desktop.

A full-screen surface on the app's black, opened from the note editor and returning to it. Everything on one bar:

- **Colour** — six swatches, all legible on black. The default is the near-white `TitleGrey`: a pen on a dark surface is what people expect, and the note's own body text is light-on-black. The rest come from the existing palette (`IndigoTint` and the brighter `CategoryColors` entries); do not invent new brand colours.
- **Nib** — three sizes, in canvas units so they mean the same thing on every screen.
- **Eraser** — stroke-level (Task 3).
- **Undo / redo.**
- **Done / Cancel.** Cancel confirms only if strokes exist.

No zoom, no pan, no layers, no shape tools, no pressure. The canvas is exactly the screen. This is scoped to "seconds with a finger", and every one of those would be a project.

**Coordinate mapping is the part to get right.** The canvas space has its long edge at 4096 and its short edge in proportion to the screen. Map screen → canvas once, at capture, and round to integers there. Rendering maps canvas → screen. A drawing must come back the same shape on a differently-proportioned screen, which is what the stored `WxH` is for.

Rendering smooths the simplified polyline with quadratic Bézier segments, so a stored scribble reads as a drawn line rather than a chain of straight segments.

- [ ] **Step 1: `SketchRenderer` first, with tests** — a two-point stroke produces a path through both mapped points; scaling to a larger size scales proportionally rather than distorting; an empty sketch produces an empty path. Geometry is testable; pixels are not, so test the geometry.
- [ ] **Step 2: The screen.** No unit tests for the composable itself; its logic lives in Task 3 and the renderer.
- [ ] **Step 3: Full `:feature-notes:testDebugUnitTest` and `:core-ui` suites; commit.**

---

### Task 5: Sketches in the note editor

**Files:**
- Modify: `feature-notes/.../ui/single/SingleNoteScreen.kt`, `SingleNoteViewModel.kt`

A `SketchSection` below `ChecklistSection`, mirroring how that block is built — **read `ChecklistSection` first and follow it**. Each sketch renders to fit the note's width at its stored aspect ratio, tapped to reopen the canvas, with a way to delete it.

Ordered by `anchor`, then uuid — the stable tie-break two devices agree on.

A toolbar affordance creates a new sketch: a new `SketchData` with `anchor = NoteBlocks.count(body, format)` (the end of the text, where it visually lands) and `order` after the last sketch at that anchor.

**Set `updatedAt` on every save.** `SketchData`'s timestamps are caller-owned; a stale one makes merges behave in ways that look like sync bugs.

- [ ] **Step 1: View-model tests first** — creating a sketch anchors it at the current block count; saving stamps `updatedAt`; deleting soft-deletes through the repository; the list is ordered by anchor then uuid.
- [ ] **Step 2–4: implement, full suite, commit.**

---

### Task 6: The desktop renders them

**Files:** the desktop's note pane, plus whatever it needs from `RecordNotesRepository.sketchRows()` (which exists and has had no consumer since PR #102).

Render-only: display, and delete. **No drawing on the desktop** — a mouse is a poor pen and building it is not free. The renderer from Task 4 is shared, so display costs little.

- [ ] **Step 1–4: a test that the desktop lists a note's sketches in the same order the phone does; implement; full `:desktop:test`; commit.**

---

### Task 7: Verification

- [ ] `rm -rf */build/test-results && ANDROID_SERIAL=46281FDAS005MQ ./gradlew verify` — foreground.
- [ ] Count honestly across every module; audit that each module with test sources reported.
- [ ] Confirm `RecordPayloadWireFormatTest` is untouched and `DATA_VERSION` is still 2 — this plan changes no protocol and must not.
- [ ] Commit, PR, merge.
