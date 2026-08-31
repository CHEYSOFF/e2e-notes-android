<!--
There is no CI on this repository, so this checklist is the only place the tests get asked about.
See docs/design/running-the-tests.md.
-->

## What changed

<!-- One or two sentences. -->

## Verification

- [ ] `./gradlew verify` passed, with a device attached.

<details>
<summary>Paste the last lines of the run</summary>

```

```

</details>

<!--
`verify` runs the JVM tests, compiles every androidTest source set, and runs the instrumented
suites on an attached device. It refuses to run without one; `-PallowNoDevice` skips the device
half and says so, and a PR that used it should say why here.

If this PR touches core-data/src/main/**/local/** or adds a Migration, the instrumented half is
not optional — that is the code path Migration4to5Test was silently failing on for months.
-->
