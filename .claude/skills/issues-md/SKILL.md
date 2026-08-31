---
name: issues-md
description: "Create or maintain a project's ISSUES.md review file, and resolve issues tracked in it. TRIGGER when: the user asks for a code review that should produce a written findings file, asks to create/update ISSUES.md, references an issue by an ID like B4, or asks to fix/resolve issues that are tracked in ISSUES.md. Covers: file structure (lettered sections ordered by severity, numbered issues, summary + detailed description), git hygiene (never committed), and the fix-and-commit workflow (one issue per commit; batch fixes commit automatically, a single requested fix is committed only after the user agrees to \"close the issue\"; ISSUES.md is only updated — summary struck through, description removed — once a fix is actually committed; commit messages that don't reference the ISSUES.md ID)."
---

# ISSUES.md: project review findings file

`ISSUES.md` is a local, uncommitted scratch file at the repo root that records the
findings of a code review in a structured, referenceable way, and tracks which of
those findings have since been fixed. It is a working document for the current
branch of work, not project documentation — it never gets committed.

## 1. Setting up the file

Before creating or writing to `ISSUES.md`, make sure it can never be committed: Add `ISSUES.md` to `.gitignore` (if not already present).

Never `git add` or commit `ISSUES.md` itself, at any point in the workflow below.

## 2. File structure

```markdown
# <Project> — Issues & Review

<One or two sentences on what this review covers.>

Each section is identified by a letter and each issue by a number, so any issue can be
referenced compactly (e.g. `B3`).

## A. <Most severe category>

A1. <One-line summary of the first issue.>

  <Full description: what's wrong, where (file/line if useful), why it matters,
  and — if non-obvious — how to fix it. Can be multiple paragraphs.>

A2. <Summary of the second issue in this section.>

  <Description.>

## B. <Next category, in descending severity>

...
```

Rules:

- **Sections are lettered (`A`, `B`, `C`, …) and ordered by descending severity.**
  Put the most severe category of finding first. This project's convention — reuse it
  unless the review's actual findings call for different categories:
  - `A` Functional bugs / errors
  - `B` Copy-paste / documentation errors
  - `C` Concurrency / robustness
  - `D` Bad patterns / code smells
  - `E` Unused / incomplete
  - `F` Security / project hygiene
  - `G` Room for improvement (suggestions)
  - `H` Positives (things done well — not issues, no fix workflow applies to these;
    keep this section last and never give its entries strikethrough treatment)
  Skip a category entirely if the review found nothing for it — don't leave an empty
  heading. Severity ordering is a judgment call: correctness/security defects outrank
  robustness gaps, which outrank style/smells, which outrank mere suggestions.
- **Within a section, issues are numbered (`1`, `2`, …) and also sorted by severity**,
  most severe first.
- **An issue is referenced by section letter + number, e.g. `B4`** — no other format.
- **Each issue has two parts, separated by a blank line:**
  1. A brief one-line summary, prefixed with its ID (`B4. <summary>`).
  2. A full description, indented on the following line(s), with concrete detail:
     what's wrong, where, why it matters, and (if it's not obvious) a fix direction.
- Number/letter gaps from removed categories are fine; don't renumber the whole file
  to close a gap.

## 3. Resolving an issue

When fixing an issue tracked in `ISSUES.md`, the commit step depends on how many
issues the user asked to fix:

- **Multiple issues fixed at once** (the user asked to fix a batch, a whole section,
  or "all of them"): fix and commit each one automatically, following the steps below
  for each. Fix exactly one issue per commit — don't bundle multiple `ISSUES.md`
  entries into one commit, even if they're related — that keeps history bisectable
  and each commit reviewable on its own.
- **A single issue fixed on its own** (the user asked to fix just one, e.g. "fix
  B4"): perform the fix, but **do not commit it automatically**. After the fix, ask
  the user whether to "close this issue" — i.e. commit the fix and, as part of that
  same step, strike it through in `ISSUES.md` per below. If they decline or don't
  respond yet, leave the fix uncommitted and the `ISSUES.md` entry untouched (full
  description still in place) so the issue is still tracked as open.

Regardless of batch size, once a fix is actually committed:

1. Between commits, prefer to leave the project in a working state: it should compile
   and the test suite should pass after each individual commit, whenever that's
   practically achievable for the fix at hand.
2. Write the commit message about the fix itself — what was wrong and what changed.
   **Never reference the `ISSUES.md` ID (e.g. "Fix B4") and never phrase the message as
   if the reader has access to `ISSUES.md`** (e.g. don't write "resolves the issue
   described in the review"). `ISSUES.md` is local and never committed, so a commit
   message that depends on it is meaningless to anyone reading the log.
3. **Only once the fix is committed**, update `ISSUES.md` — never strike through or
   trim an entry for a fix that isn't committed yet:
   - Strike through the issue's **summary line only**, using markdown strikethrough.
   - Delete the detailed description that followed it.
   - Leave the struck-through summary in place (don't delete the whole entry) — it's a
     record of what's already been handled, so it isn't re-reviewed or re-fixed later.

   Example, before:

   ```markdown
   D3. `updateReminder` opens a second DB transaction while already inside one.

     `ReminderService.updateReminder` calls `db.transaction { ... }` at line 142, but
     its only caller (`ReminderTools.reschedule`) already wraps the call in its own
     `db.transaction`. Exposed nests transactions as savepoints, so this mostly works,
     but a partial failure inside the inner block only rolls back to the savepoint,
     silently committing the outer transaction's other writes. Drop the inner
     `db.transaction` wrapper and rely on the caller's.
   ```

   After the fix is committed:

   ```markdown
   ~~D3. `updateReminder` opens a second DB transaction while already inside one.~~
   ```

## 4. Creating the file from a review

When asked to do a review that should produce `ISSUES.md`:

1. Do the review (read the relevant code, tests, config — whatever the user scoped it to).
2. Group findings into severity-ordered lettered sections per §2 above, skipping unused
   categories.
3. Write each issue as summary + blank line + detailed description, sorted by severity
   within its section.
4. Add a short intro (what the review covers) and the "referenced compactly" line, as
   in the example file.
5. Run §1's git-hygiene steps if the file doesn't already exist / isn't already ignored.
