# Blygger watch

A running log of changes to the Blygger spec and the discussion around it, measured against
ThirdMind's blyg publisher (`src/clj/hyperphor/thirdmind/blyg/`). A scheduled task
(`blygger-watch`) appends to it. Newest entries come first, and each entry covers only what is
newer than the marker.

ThirdMind's baseline: it publishes protocol 0.3 (`wire.clj:117`) with fragments, threads, pins,
reply stubs (remote fetch in `remote.clj`), the feed and the archive. It sends webmentions
for its stubs (`remote/send-mentions`) but can't receive them, and has no syndication, no `<link rel="blyg">`, no `links[]`, no `[[id | text]]`
labels and no manifest-located surface.

<!-- watch-marker:begin (UTC; the scheduled task reads and rewrites this block) -->
- spec-commit: 8d2f501 2026-11-22T01:27:50Z
- spec-0.4-revision: 20 (2026-11-21)
- origins (max `updated` in items/index.json):
  - https://venkateshrao.com/blyg/ 2026-11-22T01:38:14Z
  - https://blyg.blygger.org/ 2026-11-22T01:27:34Z
  - https://blyg.pioneeringspirit.xyz/ 2026-11-21T06:28:19Z
  - https://blyg.sachinbenny.xyz/ 2026-11-02T20:07:54Z
  - https://blyg.protocol-institute.org/ 2026-11-18T20:21:53Z
- open-comment: RFC-6 and RFC-7 to 2026-11-28; RFC-5 to 2026-12-03; RFC-3 to 2026-12-10; RFC-8 draft
<!-- watch-marker:end -->

---

## 2026-11-21: baseline

This first entry surveys the current state rather than a delta.

### Spec

0.4 is the living version (https://blygger.org/spec/0.4/; source
https://github.com/blygger/blygger-spec/blob/main/docs/protocol-v0.4.md). Its §17 is at the
twentieth revision. The local clone at `/opt/reposed/blygger-spec` stops at 2026-11-16, so it
lacks revisions 17 to 20; read GitHub instead. The reference client is blygger-studio 0.68.0.
venkateshrao, blyg.blygger.org and pioneeringspirit already serve `"blyg": "0.4"`. Sachin's
blyg and ammdi still serve 0.3. §3.1 says readers accept any `0.x`.

What 0.4 asks of a publisher like ThirdMind, by risk:

1. **An item's kind is fixed at first publication** (§5.3, rev 13, decision #100). This is a
   MUST, and **ThirdMind breaks it today.** `wire/next-entry` writes the new `kind` into the
   next version (`wire.clj:84`), and `publish.clj:73` even writes a "Now a thread." change
   note. A `#blyg` block that gains a transclusion child turns from a fragment into a thread.
   §5.3 also says a thread that quotes nothing is valid, and that a client should create
   threads by default.
2. **Client-private bracket forms are rewritten before publish** (§10.1, decision #70). Any
   bracket text other than `[[id]]` or `[[id | text]]` is literal. `content.clj` rewrites
   `[[Page]]`, `#tag` and `{{macro}}`. Check that `((uuid))` block refs and unpublished page
   links never reach `content_md` raw. Rev 14 (#102) also makes a directive line inside a
   fragment a publish error, and resolves relative URLs against the identity origin.
3. **Unicode** (§5.1, rev 15, #103). `content_md` MUST NOT contain C0 controls (tab, LF and
   CR are allowed) or unpaired surrogates, and SHOULD be NFC. The hash is over the bytes
   served. This touches `content.clj` and `wire/content-hash`.
4. **Multi-item publish order** (§7, §13.7, rev 3 and rev 18). Publish one feed event per
   item, with the thread last, so readers can fold the burst. Check `publish.clj`'s ordering
   when a thread and its new fragments go out together.
5. **Additive members that readers can ignore:**
   - `links[]` and the `link` mention relation (§10.3a, rev 4).
   - `data-blyg-label` (§10.2).
   - `syndication` (§5.10, rev 19; Studio 0.67.0 emits it). It is rewritten in place,
     outside the hash, and never gets a version of its own.
   - MathML with a TeX annotation (§5.2 SHOULD, rev 16) for Logseq `$$`.
   - `generated[]` (§5.7). Rev 20 adds that a correcting pass is not generation and a
     composing pass is. This is relevant if `#AskClaude` answers ever publish.
   - §16.15, list structure in a partial bake, is ruled but not yet built.
6. **The `"blyg"` key.** Bump it to `"0.4"` in `wire.clj:117` once items 1 to 3 hold. The
   key is informative only.

Open for comment (https://github.com/blygger/blygger-spec/tree/main/docs/rfcs):

- RFC-6 (sealed content) and RFC-7 (payments): to 2026-11-28. These are Level 3 / 0.5
  material (`docs/v0.5-plan.md`) and probably don't affect ThirdMind.
- RFC-5 (enterprise deployment): to 2026-12-03.
- RFC-3 (pace-layered order): to 2026-12-10.
- RFC-8 (editorialized quotation, #116): a draft, posted 2026-11-22.

TN-8, "Where does my change belong?", is a contributor ladder. It says any change that makes
another blyg receive different bytes is a spec question
(https://blyg.blygger.org/t/2d244a6ff5tz7mfe5gd4m41a4p/).

### Discourse

- **Someone quoted ammdi, and ThirdMind never heard about it.** Sachin's blyg transcludes
  ammdi item `7yyd79wjn3c274bad51vytkc1c` v2
  (https://blyg.sachinbenny.xyz/t/4kxa7dysg27906qhaprbbwejc2/, 2026-10-17). ammdi's manifest
  has no `webmention` key, so no notice can arrive, and Sachin's blyg advertises no endpoint
  either. This is the concrete cost of the webmention gap.
- **Mentions are underused.** Venkat says mentions should be used more, and the address book
  is disconnected from following
  (https://venkateshrao.com/blyg/t/2ga60x6d60nkqc1q30n76rfsr4/). PAtwater asks what triggers a
  mention, and whether a quote counts the way a transclusion does
  (https://blyg.pioneeringspirit.xyz/t/6d3y5h9trbdw32prag35ew70vr/).
- **Someone is looking for an automated blyg like ThirdMind.** PAtwater asks whether anyone
  has set up a fully automated blyg to take ideas and architecture from
  (https://blyg.pioneeringspirit.xyz/f/0x0nwp2h7sz33qjdcmjxg5a5tt/). ThirdMind is one; it's
  the user's call whether to answer.
- **Clients needn't be single-blyg.** Venkat says a client doesn't have to publish to a
  single blyg and could OAuth into many. He leaves open whether reading subscriptions belong
  to the server or the client (https://venkateshrao.com/blyg/t/20pz14q42tz38ry9hz9pbv6fpd/).
- **"Philosophy of Blygger."** Venkat lists twelve design principles. Among them: AI
  generation happens before publish, there are no influencer metrics on the wire, you can do
  anything that doesn't change the wire, and DNS is the only identity
  (https://venkateshrao.com/blyg/t/4h7h9tfbcp4mwz2d8zjs8eawmx/). This fits ThirdMind's model.
- **Level 0 vs Level 1.** RSS is Level 0, and a site becomes a blyg at Level 1. The blygger.com
  directory's main tab lists only L1 and above
  (https://venkateshrao.com/blyg/t/5gefbbrxxkbrpbe8n9kcbm920t/). Venkat's blogroll already
  lists ammdi.
- **Tangling** (Studio 0.63.0): `![[+ …]]` spans become their own items, and `[[+ text]]` makes
  placeholders (https://blyg.blygger.org/t/0enf8zg43d5rtvsr5xre4gktbd/). This is analogous to
  Logseq child blocks becoming fragments; ThirdMind's model already resembles it.

### Next steps for ThirdMind

1. Freeze `kind` after v1 in `wire/next-entry` and `publish.clj`. A fragment that gains
   children stays a fragment, or gets a new thread item. Alternatively, always create threads
   (§5.3 SHOULD).
2. Audit `blyg/content.clj` against §10.1 and §5.1: no raw brackets, no C0 controls, NFC.
3. Add a webmention receiver, or at least the manifest key, so quotes like Sachin's get
   noticed. See `design/blyg-replies.md`.
4. Then bump to 0.4 (`wire.clj:117`) and emit `links[]` in `wire/item-json`.
