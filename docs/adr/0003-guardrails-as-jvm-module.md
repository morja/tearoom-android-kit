# ADR-0003 · Guardrail rules as a pure JVM module

Status: accepted, 6 November 2026 (user decision, Mathis; REV-2 proposal Z1 in the Cockpit).

## Decision

- The scanning code behind the apps' guardrail tests (colour literals, raw `dp`/`sp`, layer imports,
  a pattern allowed in one file only, complete translations, counted baselines) lives in the module
  `:testing` (`one.tearoom:testing`), used by the apps as a `testImplementation` dependency.
- The module is plain Kotlin on the JVM: no Android dependency, no test framework. It needs only
  `java.nio` and `javax.xml`, so it compiles with the Kotlin JVM plugin and runs in any unit test.
- Rules return hits (file, line, text) or a report; the app's test asserts. That keeps JUnit 4
  (Weatherfold, Paperboat, Lazy Moves) and kotlin.test on JUnit 5 (Dahlia `core`) both working.
- Allowed files are paths relative to the source root (`ui/Theme.kt`, `ui/theme`), not bare names.

## Reasons

- Four apps and the Cockpit template carried three slightly different copies of the same regexes;
  a fix in one (paths instead of names, every `strings*.xml`, plural and format checks) reached none.
- A JVM module is testable in the kit itself with a small fixture tree, without an emulator or Robolectric.

## Consequences

- An app that tightens a rule for itself passes its own pattern or allowed set; the defaults stay the
  shared baseline of the AGENTS.md guardrails.
- The kit build now applies the Kotlin JVM plugin next to AGP; both follow the newest app (rule 4).
