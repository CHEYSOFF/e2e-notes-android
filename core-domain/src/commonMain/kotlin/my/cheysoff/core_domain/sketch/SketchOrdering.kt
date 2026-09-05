package my.cheysoff.core_domain.sketch

import my.cheysoff.core_domain.model.SketchData

/**
 * Sketches in the one order both platforms render them in: by [SketchData.anchor], ties broken by
 * [SketchData.id].
 *
 * This is the total order the flat, below-the-text layout needs today -- see
 * `docs/design/sketch-blocks.md`'s 2026-09-05 amendment. Deliberately NOT [SketchData.order] at any
 * position in the key: `order` is scoped per-anchor for a future inline layout (see that field's
 * own KDoc), which neither platform implements yet.
 *
 * Both the phone (`SingleNoteViewModel.sortSketches`, a one-line delegation to this function) and
 * the desktop (`sketchesForDisplay`) call this directly, rather than each carrying its own copy of
 * the rule. An earlier version of this project kept two copies, pinned together only by mirrored
 * tests on each side -- exactly the kind of thing that can drift silently: if the two ever
 * disagreed, the same note would list its drawings in a different order on each of a user's two
 * devices, and no test on either platform alone would catch it. One compiled function shared by
 * both removes that hazard instead of merely detecting it.
 */
fun sortSketches(sketches: List<SketchData>): List<SketchData> =
    sketches.sortedWith(compareBy({ it.anchor }, { it.id }))
