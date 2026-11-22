# UI Guidelines (FASE 15)

Status: living document. Describes the actual design tokens/components in
`frontend/src/styles.scss` and `frontend/src/app/shared/components/**` - not an aspirational
style guide written ahead of the code.

## 1. Design intent

Enterprise AI assistant, not a chatbot demo (brief FASE 15 section 3/45): restrained palette,
clear information hierarchy, minimal motion, no gradients/glassmorphism/3D/neon. The home screen
is the AI Assistant itself, never a metrics dashboard (brief section 24).

## 2. Colour tokens (`:root` custom properties, `styles.scss`)

| Token | Value | Use |
|---|---|---|
| `--app-color-background` | `#f6f7f9` | Page background |
| `--app-color-surface` | `#ffffff` | Cards, panels, composer |
| `--app-color-surface-alt` | `#f0f2f5` | Subtle fills (table headers, hover) |
| `--app-color-border` | `#e0e3e8` | Hairline borders |
| `--app-color-text-primary` | `#1a2130` | Headings, primary content |
| `--app-color-text-secondary` | `#5b6472` | Body/supporting text |
| `--app-color-text-muted` | `#6b7280` | Least-emphasis text - deliberately tuned to reach WCAG AA's 4.5:1 contrast on white for normal text, not just a decoratively lighter gray |
| `--app-color-accent` | `#0f4c81` | Primary actions, links, active nav |
| `--app-color-success` / `-soft` | green pair | `EMBEDDED`, `PASSED`, `ACTIVE`/`APPROVED` |
| `--app-color-warning` / `-soft` | amber pair | Human oversight required, PII notice |
| `--app-color-danger` / `-soft` | red pair | `FAILED`, blocked, errors |
| `--app-color-info` / `-soft` | blue pair | `LIMITED` risk, `PROCESSING` |

`StatusBadge` (`shared/components/status-badge`) is the single component every status/outcome/risk
indicator in the app uses - every variant pairs a colour with a text label (never colour alone,
brief section 16/26).

## 3. Typography

Inter (Google Fonts, loaded in `index.html`) for both body and heading text - one type family,
weight (400/500/600/700) carries the hierarchy rather than mixing families. Material Symbols
Rounded is the one icon font (`fontSet="material-symbols-rounded"` on every `mat-icon`) - no
emoji as primary iconography (brief section 7).

## 4. Spacing, radius, shadow

4px base spacing scale (`--app-space-1` through `--app-space-7` = 4/8/12/16/24/32/48px),
`--app-radius-sm/md/lg` (6/10/14px), `--app-shadow-sm/md` (both subtle - no heavy drop shadows,
brief section 18).

## 5. Components

- **StatusBadge** - colour + label, 5 semantic variants (`success`/`warning`/`danger`/`info`/`neutral`)
- **LoadingIndicator** - one honest, generic loading message with a tasteful 3-dot pulse animation
  (never a fabricated multi-stage "Searching… Reviewing… Generating…" sequence - the backend has
  no streaming/staged-progress signal to represent honestly, brief section 16)
- **TechnicalDetails** - collapsible `mat-expansion-panel` for trace id / error code, never shown
  expanded by default (brief section 6/14/25)
- **EmptyState** - icon + title + optional description, used identically across Documents/
  Governance/Audit/Evaluation for "nothing here yet"
- **SecurityBanner** - the one blocked-by-guardrail visual treatment (`role="alert"`), never
  exposes the matched pattern/rule/prompt (brief section 14)

## 6. Layout

`AppShell` = `Header` (64px, app title + real health-derived status dot) + `Sidenav` (240px,
5 nav items + footer status/provider/environment) + routed content. `mat-sidenav` switches
`mode="side"` (desktop, always open) / `mode="over"` (handset, `BreakpointObserver` on
`Breakpoints.Handset`, closed by default, toggled via the header's hamburger button).

## 7. Motion

Deliberately minimal (brief section 48): the loading-indicator pulse, `mat-sidenav`'s built-in
slide transition, `mat-expansion-panel`'s built-in expand/collapse. `@media
(prefers-reduced-motion: reduce)` disables the pulse animation globally. No page-transition
animations, no scroll-triggered effects.

## 8. Accessibility (WCAG-informed practice)

- Every purely decorative icon carries `aria-hidden="true"` (paired with adjacent visible text or
  already-labelled by its parent button/region) - every icon that is the *only* content conveying
  meaning (e.g. the evaluation pass/fail icon) carries its own `aria-label` instead.
- `app-message-list` has `role="log" aria-live="polite"` so new chat turns are announced to screen
  readers without needing to move focus.
- `SecurityBanner` uses `role="alert"` for immediate announcement.
- All interactive elements are real `<button>`/`<a>`/form controls - reachable and operable via
  keyboard by default, no custom `div`-as-button patterns.
- `:focus-visible` gets an explicit 2px accent outline (`styles.scss`) - focus is never suppressed.
- `--app-color-text-muted` was specifically tuned (see section 2) after finding the original tone
  fell short of WCAG AA contrast for normal text.

## 9. Responsive

Single breakpoint drives the structural change (sidenav mode, header hamburger visibility):
Angular CDK's `Breakpoints.Handset` (`900px` in the header/app-shell SCSS, matching CDK's own
handset range). Below that: hamburger-toggled overlay sidenav, condensed header (subtitle and
status label hidden, dot retained). Grids (`citation-list`, `evaluation-metrics`,
`document-upload__fields`) use `repeat(auto-fit/auto-fill, minmax(...))` so they reflow naturally
at any width without additional per-breakpoint rules. Tables (`documents-page`, `audit-page`,
`evaluation-run`) are plain HTML tables with no forced `min-width`, so narrow viewports get the
browser's native horizontal scroll rather than broken layout.

## 10. What this UI deliberately does not do

No gradients, no glassmorphism, no 3D, no neon, no gamified/chatbot-toy styling (brief section 3).
No invented metrics, health claims, or compliance language (brief section 63) - every status
indicator, badge, and figure traces to a real backend field.
