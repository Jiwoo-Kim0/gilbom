**Source visual truth**

- Path: `/home/ssafy/common-proj/web/docs/img/main.png`
- Pixel dimensions: 2878 × 1536
- State: desktop defect screen with expanded navigation and the `≪` boundary control
- The screenshot is the user-reported problem state: the navigation control appears to have been pushed behind another layer.

**Implementation evidence**

- Screenshot: `/home/ssafy/common-proj/web/.artifacts/sidebar-front-layer.png`
- Pixel dimensions: 2878 × 1536
- Browser viewport: 1439 × 768 CSS pixels at device scale factor 2
- State: desktop defect screen with expanded navigation

**Full-view comparison evidence**

- Comparison: `/home/ssafy/common-proj/web/.artifacts/sidebar-front-layer-full-comparison.png`
- The problem capture and corrected browser render are shown together at the same normalized viewport and density.
- The navigation width, toggle position, top bar, search region, and map card retain their existing proportions.

**Focused region comparison evidence**

- Comparison: `/home/ssafy/common-proj/web/.artifacts/sidebar-front-layer-comparison.png`
- The `≪` control remains centered on the navigation/content boundary and its complete icon and clickable surface remain visible.
- The corrected control is a positive stacking item and is the top hit target in its visible region.

**Findings**

- No remaining P0, P1, or P2 issue was found in the requested layer correction.
- The implementation capture uses mocked map data and the QA container lacks the project Korean font package; these differences do not affect the measured control position or stacking behavior.

**Required fidelity surfaces**

- Fonts and typography: unchanged by this correction.
- Spacing and layout rhythm: control dimensions and 44% vertical anchor are unchanged.
- Colors and visual tokens: existing border, surface, and shadow tokens are preserved.
- Image quality and asset fidelity: existing logo, map component, and Lucide chevron icon are preserved.
- Copy and content: no user-facing copy changed.

**Primary interactions and console**

- Playwright verified computed `z-index: 1`.
- Playwright verified the visible control is the top pointer hit target.
- Playwright clicked `네비게이션 접기` and verified `네비게이션 펼치기` appeared.
- Browser console errors: none.

**Comparison history**

- Earlier P1: `.nb-sidebar-toggle` used `z-index: -1`, placing the interactive control in a negative child layer.
- Fix: changed the control to `z-index: 1` while preserving the bookmark position and complete `≪ / ≫` icon.
- Post-fix evidence: Playwright interaction passed and both full and focused comparison captures were reviewed.

final result: passed
