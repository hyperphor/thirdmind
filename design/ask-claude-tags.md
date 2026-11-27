# #AskClaude / #AskedClaude: separate tags for asking and answers

Status: proposal, nothing implemented. 2026-11-22.

## Problem

One tag, `#AskClaude`, currently does three jobs:

1. **Invocation.** `… #AskClaude` (or `#AskClaudePage`) on a block asks Claude.
2. **Answer wrapper.** The block holding the answer is a bare `#AskClaude`, with the answer
   nested under it.
3. **Label.** Hand-written `#AskClaude (really chatgpt)` and similar mark pasted AI text.

The handler has to guess which job a given block is doing, and it guesses wrong:

- `ask_claude/wrapper?` treats any block *starting* with `#AskClaude` as a wrapper. So
  `#AskClaude grab all the post links…` (Asabiyyah, 2026-11-22) was skipped as an answer, and
  `in-answer?` would also have stopped smart-url from running on it.
- Moving the tag to the end didn't help. Triggers come from `ingest/new-tags`, which fires
  only when a block's uri didn't carry the tag before. The block already had it (at the
  front), so it wasn't "new". At present the only way to re-trigger is to remove the tag, let
  a sync pass, and then add it back.
- The logseq-claude plugin leaves the tag on the prompt after answering, so `#AskClaude` on a
  block doesn't tell you whether it has been answered.

## Proposal

- `#AskClaude` / `#AskClaudePage` mean **only** "please answer this".
- `#AskedClaude` marks **only** Claude's output: the wrapper block, with the answer nested
  under it. Hand-written labels for pasted AI text use it too.
- Once a prompt is answered, its trigger tag is removed (ThirdMind already strips it). The
  prompt is ordinary text, and the `#AskedClaude` child under it records that it was answered.

```
- grab all the post links from above and insert them here
	- #AskedClaude
		- answer …
```

### Detection becomes level-triggered

With the tags separated, the file says what is pending: a block carrying `#AskClaude` is
waiting for an answer. Triggers can then be "blocks with a trigger tag and no `#AskedClaude`
child", which has three consequences:

- Moving the tag, editing the block, or restarting the watcher no longer matter. Prompts left
  over from while the watcher was down get answered when it comes back.
- `wrapper?` becomes a plain tag check (`#AskedClaude` among the block's refs), and the
  position heuristic goes away.
- The guard against asking twice moves from the diff to the file: once the answer is written,
  the trigger tag is gone. We should keep an in-flight set (the action runner already keys
  events) so that a prompt isn't asked twice while its answer is being written.

The catch is that this only works once every old answered prompt has lost its trigger tag.
Otherwise the first sync re-asks all of them, so the migration has to land before (or
together with) the switch to level-triggering.

## The graph today (ammdi, 2026-11-22)

| What | Count |
|---|---|
| Files mentioning `#AskClaude*` | 172 (36 journals) |
| Bare `#AskClaude` wrappers | 224 (222 with nested answers, 2 with answers as *siblings*: `AskClaude/Voice`, `Weird Studies/Murakami & Borges`) |
| Prompts with the tag after text | 82 (75 with answer children directly, no wrapper; 1 with a wrapper child; 5 with no children) |
| Tag first, followed by text | 9, all labels (`#AskClaude (really chatgpt)`, `(via the app …)`, `(really #AskZirp)` …) |
| `#AskClaudePage` | 0 |
| `#AskedClaude` already | 1 (`What Lives?`: `What would Marvin Minsky think of this paper #AskedClaude`) |

The 5 childless prompts are `the shadow`, `aseity`, `2026_10_10` and the two on Asabiyyah,
and they are probably genuinely pending. The 75 "direct children" prompts follow the
plugin-era layout, where the hover rendering shows the tagged block's children as the answer.
Some of those children may be the user's own follow-ups rather than Claude's text, so the
migration can't assume they all are.

## Migration (one-off script, `lein ask-migrate [--dry-run]`)

It runs through `graph_write` (quiet wait, atomic write), one file at a time, and is
idempotent. Every block category gets one rewrite:

| Shape | Rewrite |
|---|---|
| Bare `#AskClaude` wrapper | `#AskedClaude` |
| Wrapper with sibling answers (2) | Report only, fix by hand |
| Tag-first label (9) | `#AskedClaude (really chatgpt)`, i.e. swap the tag and keep the text |
| Prompt with trailing tag **and** answer children (75) | Drop the tag from the prompt and wrap the existing children in a new `#AskedClaude` child |
| Prompt with trailing tag and a wrapper child (1) | Drop the tag from the prompt and rename the wrapper |
| Prompt with no children (5) | Leave as is: these are real pending asks |
| `[[AskClaude]]` link forms | Same rules as the `#` forms |

The dry run prints the per-file classification plus a diff, so the 75 wrapped-children cases
can be checked before anything is written. Logseq's git autosave is the undo. Its commits
give a per-file before/after, but a commit of our own before and after the run would be
cleaner.

Separately: do we rename the `AskClaude` page? No. It's the hub page for the tool. We do need
an `AskedClaude.md` page, which Logseq creates on first use anyway, and maybe it should alias
to or link back to `AskClaude`.

## Code changes

ThirdMind:

- `ask_claude.clj`: an `answer-tag "AskedClaude"` constant. `answer-lines` writes it,
  `wrapper?` becomes a refs check, `in-answer?` follows, `triggers` becomes level-based (see
  above), and `prompt` skips `#AskedClaude` subtrees.
- `watch.clj`: the ask-claude `:detect` reads the trigger set from current blocks instead of
  `new-tags`. smart-url keeps `in-answer?`.
- `ui/pages.clj`, `blyg/content.clj`: the hover/`<details>` rendering keys on `#AskedClaude`
  (the wrapper), not on the tagged prompt. After migration, every answer sits under a wrapper,
  so the "prompt's children are the answer" case goes away.
- Tests: `actions_test`, `content_test`, `server_test` and `ingest_test` (tag set), plus the
  fixture `test/resources/graph/pages/__Erlebnis__.md`.

Elsewhere:

- **logseq-claude plugin** (`index.js`, `DEFAULT_RESPONSE_TAG = '#AskClaude'`): change it to
  `#AskedClaude` and strip the trigger from the prompt. Otherwise the plugin keeps producing
  old-style blocks, which level-triggering would re-ask. Alternatively retire the plugin in
  favour of the watcher.
- **goddinpotty**: `:hover-tags ["AskClaude"]` → `["AskedClaude"]`, plus
  `word_history.clj` (`ask-re`, the "block under an #AskClaude block is Claude's" rule) and
  `export/syndicate.clj`. Until it ships, the site renders migrated pages without hover boxes.
- **blyg**: answers re-render as new versions of any published item that contains one. Check
  with `lein blyg diff` after migrating, and expect the corresponding version bumps.

## Order

1. Land the code across ThirdMind, plugin and goddinpotty, accepting both tags as answers
   for now, with detection still edge-triggered.
2. Run the migration (dry run first, then for real) and commit the ammdi repo.
3. Switch ThirdMind to level-triggered detection and drop `#AskClaude` as an answer marker
   everywhere.
4. Rebuild the site, then `lein blyg publish`.

## Open questions

- Should a label (pasted AI text) be `#AskedClaude`, or does it deserve its own tag, e.g.
  `#AI`? Folding it in keeps rendering simple, but it loses the "not via the tool"
  distinction (and some aren't Claude at all: "really chatgpt", "#AskZirp").
- Level-triggering means any `#AskClaude` typed anywhere (in a journal, say) costs an API
  call once quiet-ms passes. That's how edge-triggering behaves too, except for restarts. Is
  catch-up after a restart wanted, or should only prompts newer than the watcher's start
  time count?
- `What Lives?` already uses `#AskedClaude` as "I asked this elsewhere". That fits the label
  meaning, and the migration leaves it alone.
