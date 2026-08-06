**Source visual truth**

- Path: `/home/ssafy/common-proj/web/docs/img/main.png`
- Pixel dimensions: 2878 × 1536
- State: desktop defect screen with expanded navigation and the original wide `≪` boundary control

**Implementation evidence**

- Screenshot: `/home/ssafy/common-proj/web/.artifacts/sidebar-bookmark-layer.png`
- Pixel dimensions: 2878 × 1536
- Browser viewport: 1439 × 768 CSS pixels at device scale factor 2
- State: expanded navigation with the corrected bookmark-layer control

**Full-view comparison evidence**

- Comparison: `/home/ssafy/common-proj/web/.artifacts/sidebar-bookmark-layer-full-comparison.png`
- Existing layout proportions, navigation width, top bar, search region, and map card remain unchanged.
- The corrected control occupies less horizontal space over the main content.

**Focused region comparison evidence**

- Comparison: `/home/ssafy/common-proj/web/.artifacts/sidebar-bookmark-layer-comparison.png`
- The exposed width decreased from 34px to 22px and the height increased from 44px to 52px.
- The navigation surface visibly covers the control’s overlapping 10px, creating an actual behind-layer bookmark relationship.

**Findings**

- No remaining P0, P1, or P2 issue was found in the requested layer and proportion correction.
- The QA container lacks the project Korean font package and uses mocked map data; neither affects the measured control geometry or stacking.

**Required fidelity surfaces**

- Fonts and typography: unchanged.
- Spacing and layout rhythm: 22px exposed width, 52px height, and the existing 44% vertical anchor.
- Colors and visual tokens: existing surface, border, hover, focus, and shadow tokens remain unchanged.
- Image quality and asset fidelity: existing logo, map component, and Lucide chevrons remain unchanged.
- Copy and content: unchanged.

**Primary interactions and console**

- Navigation and toggle are sibling layers inside `.nb-sidebar-layer`.
- Navigation uses `z-index: 2`; toggle uses `z-index: 1`.
- Playwright verified the overlapping point resolves to the navigation layer.
- Playwright verified the exposed point resolves to the toggle and remains clickable.
- Playwright clicked collapse and verified the expand control appeared.

**Comparison history**

- Earlier P1: changing only the toggle’s `z-index` could not establish a reliable behind-navigation relationship because the control was a child of the navigation surface.
- Fix: introduced a shared layer wrapper and made navigation and toggle sibling stacking items.
- Earlier P2: the 34px protrusion was too wide and risked covering content.
- Fix: reduced protrusion to 22px and increased height to 52px for a slimmer vertical bookmark.
- Post-fix evidence: Playwright geometry, hit-target, collapse interaction, and visual comparison all passed.

final result: passed
