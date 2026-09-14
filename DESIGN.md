---
name: EPR
description: A secure, high-scanability clinical workbench for Philippine outpatient care and provider operations.
colors:
  primary: "#245ce5"
  primary-hover: "#184bc9"
  ink-strong: "#10234a"
  foreground: "#192943"
  secondary-text: "#65738b"
  canvas: "#f6f8fc"
  surface: "#ffffff"
  selection: "#edf4ff"
  navigation-selection: "#eaf1ff"
  border: "#e0e7f0"
  input-border: "#b8c9ec"
  success: "#087953"
  success-soft: "#eaf8f2"
  warning: "#986014"
  warning-soft: "#fff5e4"
  error: "#a52c3d"
  error-soft: "#fff0f0"
  violet: "#7652c4"
  violet-soft: "#f1edfc"
typography:
  display:
    fontFamily: "Roboto, system-ui, sans-serif"
    fontSize: "clamp(36px, 3vw, 48px)"
    fontWeight: 600
    lineHeight: 1.05
    letterSpacing: "-1.4px"
  headline:
    fontFamily: "Roboto, system-ui, sans-serif"
    fontSize: "34px"
    fontWeight: 600
    lineHeight: 1.15
    letterSpacing: "-0.85px"
  title:
    fontFamily: "Roboto, system-ui, sans-serif"
    fontSize: "17px"
    fontWeight: 600
    lineHeight: 1.4
    letterSpacing: "-0.28px"
  body:
    fontFamily: "Roboto, system-ui, sans-serif"
    fontSize: "14px"
    fontWeight: 400
    lineHeight: 1.5
    letterSpacing: "normal"
  field:
    fontFamily: "Roboto, system-ui, sans-serif"
    fontSize: "17px"
    fontWeight: 400
    lineHeight: 1.4
    letterSpacing: "normal"
  label:
    fontFamily: "Roboto, system-ui, sans-serif"
    fontSize: "12px"
    fontWeight: 500
    lineHeight: 1.4
    letterSpacing: "normal"
  action:
    fontFamily: "Roboto, system-ui, sans-serif"
    fontSize: "14px"
    fontWeight: 500
    lineHeight: 1.4
    letterSpacing: "normal"
rounded:
  compact: "5px"
  control: "8px"
  field: "10px"
  surface: "12px"
  feature: "16px"
  folded: "11px 11px 11px 3px"
  full: "999px"
spacing:
  xs: "4px"
  sm: "8px"
  md: "12px"
  lg: "16px"
  xl: "20px"
  2xl: "24px"
  3xl: "32px"
  4xl: "48px"
components:
  button-primary:
    backgroundColor: "{colors.primary}"
    textColor: "{colors.surface}"
    typography: "{typography.action}"
    rounded: "{rounded.control}"
    padding: "8px 16px"
    height: "40px"
  button-primary-hover:
    backgroundColor: "{colors.primary-hover}"
    textColor: "{colors.surface}"
    typography: "{typography.action}"
    rounded: "{rounded.control}"
    padding: "8px 16px"
    height: "40px"
  button-outline:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.foreground}"
    typography: "{typography.action}"
    rounded: "{rounded.control}"
    padding: "8px 16px"
    height: "40px"
  button-ghost:
    backgroundColor: "transparent"
    textColor: "{colors.primary}"
    typography: "{typography.action}"
    rounded: "{rounded.control}"
    padding: "8px 16px"
    height: "40px"
  input-auth:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.ink-strong}"
    typography: "{typography.field}"
    rounded: "{rounded.field}"
    padding: "0 16px"
    height: "58px"
  navigation-selected:
    backgroundColor: "{colors.navigation-selection}"
    textColor: "{colors.primary}"
    typography: "{typography.action}"
    rounded: "{rounded.control}"
    padding: "12px 15px"
    height: "48px"
  card-operational:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.foreground}"
    rounded: "{rounded.surface}"
    padding: "20px"
  status-chip:
    backgroundColor: "{colors.selection}"
    textColor: "{colors.primary}"
    typography: "{typography.label}"
    rounded: "{rounded.compact}"
    padding: "4px 8px"
---

# Design System: EPR

## Overview

**Creative North Star: "The Secure Clinical Workbench"**

EPR presents a calm, deliberate operational environment: information is easy to scan, actions are unmistakable, and security or scope boundaries are stated in plain language. The visual system uses a pale clinical canvas, crisp white working surfaces, navy hierarchy, and royal-blue action cues. Brand expression comes from disciplined spacing, the folded-document mark, and precise Lucide line icons rather than decorative healthcare imagery.

The system is compact without feeling compressed. Fine borders and pale selection fills separate structure; colour is reserved for action, status, and bounded illustration. The provider sign-in surface may use a contained blue workflow field to mark the secure threshold, while authenticated clinical and provider workspaces return to flat, productive surfaces.

**Key Characteristics:**

- Calm, high-scanability application density
- Navy hierarchy with royal-blue action and selection
- White bordered surfaces on a pale neutral canvas
- Modest radii and restrained depth
- Text-labelled status and availability states
- Responsive sidebar, rail, and bottom-navigation shells

## Colors

The palette is cool, clinical, and restrained: navy carries authority, royal blue carries interaction, and semantic hues appear as labelled accents on pale grounds.

### Primary

- **Royal Action Blue** (`primary`): primary actions, active navigation, focus, and selected clinical context.
- **Pressed Royal Blue** (`primary-hover`): hover emphasis for primary actions only.

### Secondary

- **Verified Green** (`success`) with **Verified Mist** (`success-soft`): connected, current, or verified-positive states accompanied by text.
- **Caution Amber** (`warning`) with **Caution Cream** (`warning-soft`): restrained attention and pending-category accents, never an unlabeled warning signal.

### Tertiary

- **Coordination Violet** (`violet`) with **Coordination Mist** (`violet-soft`): secondary workflow categories such as grants or orders, used sparingly.

### Neutral

- **Threshold Navy** (`ink-strong`): major headings, brand copy, and high-authority statements.
- **Clinical Ink** (`foreground`): default application text.
- **Slate Annotation** (`secondary-text`): supporting copy, timestamps, metadata, and inactive labels.
- **Pale Clinical Canvas** (`canvas`): authenticated workspace background.
- **Working White** (`surface`): forms, cards, sidebars, top bars, and primary reading surfaces.
- **Selection Wash** (`selection`): calm information panels, mobile selection, and blue icon tiles.
- **Navigation Selection** (`navigation-selection`): the slightly denser blue wash behind active desktop and tablet navigation.
- **Cool Hairline** (`border`): routine dividers and surface outlines.
- **Field Blue Hairline** (`input-border`): authentication-field boundaries before focus.
- **Clinical Error** (`error`) with **Error Wash** (`error-soft`): inline validation and request failures with explicit text.

**The Label Before Colour Rule.** Colour reinforces a state; visible text or an icon-plus-label carries its meaning.

**The Contained Gradient Rule.** The blue gradient belongs to the illustrative unauthenticated threshold. Authenticated work surfaces remain flat and neutral.

## Typography

**Display Font:** Roboto with system sans-serif fallback  
**Body Font:** Roboto with system sans-serif fallback  
**Label Font:** Roboto with system sans-serif fallback

**Character:** One self-hosted sans-serif family keeps clinical reading predictable across headings, forms, controls, and dense metadata. Weight, size, and compact negative tracking establish hierarchy without introducing a decorative display face.

### Hierarchy

- **Display** (600, fluid 36–48px, 1.05): the provider sign-in welcome only; mobile reduces it to 34px.
- **Headline** (600, 34px, 1.15): major provider workspace headings; responsive layouts reduce it to 29px.
- **Title** (600, 17px, 1.4): operational sections and card headings; larger empty-state titles use 20–23px.
- **Body** (400, 14px, 1.5): core explanatory text and control copy, with secondary copy commonly constrained to readable measures.
- **Field** (400, 17px, 1.4): desktop and tablet authentication input text; mobile reduces it to 16px.
- **Label** (500, 12px, 1.4): metadata, status copy, and compact controls; uppercase appears only for bounded scope labels.
- **Action** (500, 14px, 1.4): shared buttons and navigation rows.

**The One-Family Rule.** Use Roboto for headings, body text, form controls, and labels; hierarchy comes from scale and weight, not a second typeface.

**The Operational Case Rule.** Use sentence case by default. Reserve uppercase for compact, explicit scope or system-state labels.

## Layout

Authenticated workspaces use persistent context around a bounded content region. Desktop uses a 228–236px left sidebar and a sticky white top bar; provider content is capped at 1480px and padded by roughly 32px. Between desktop and mobile, the provider shell collapses to an 88px icon rail while preserving the top bar and content hierarchy. At 700px and below, the rail disappears and a fixed four-destination bottom navigation takes over, with content padded clear of it.

The sign-in threshold uses a split desktop composition: a focused white form column with a minimum width of 480px and an illustrative workflow field filling the remainder. Below 900px the illustration stacks above the form; below 600px it compresses to a 262px branded workflow summary so the form and primary action remain immediately reachable.

Spacing follows a practical 4px-derived rhythm, with 8–16px inside compact controls, 20–24px inside working cards, and 28–34px around main content. Grids reflow rather than shrink important labels: three provider status cards become one column on mobile, and summary definitions change from columns to labelled rows.

**The Context Persists Rule.** Responsive changes may compress navigation and illustration, but must retain page identity, explicit scope, primary action, and labelled system state.

## Elevation & Depth

The system is flat by default. White surfaces are separated from the pale canvas with cool 1px borders, while selection uses tonal blue fills rather than lift. Shadows are limited to the sign-in workflow canvas and the dominant sign-in action, where they help define the unauthenticated threshold without carrying into the operational workspace.

### Shadow Vocabulary

- **Threshold Canvas** (`0 18px 38px #06183c24`): the floating illustrative workflow panel on the blue sign-in field.
- **Primary Action** (`0 5px 14px #245ce521`): the full-width sign-in action.
- **Compact Action** (`0 3px 6px #245ce512`): low emphasis under compact, consequential dashboard actions.

**The Flat-by-Default Rule.** Use borders and tonal layering for routine structure; reserve shadow for focused threshold or action emphasis.

## Shapes

Controls use gently rounded 8px corners, authentication fields use 10px, and working cards use 12px. Feature panels may reach 16px, while avatars and workflow nodes are circular. The EPR mark is the signature exception: an asymmetrical 11px/3px folded-document silhouette with a clipped pale corner.

Fine 1px borders remain visible on white surfaces. Pills are reserved for chips and compact status labels, not general containers. Icon tiles are usually square or slightly vertical with 11–12px corners, keeping line icons optically centred and easy to scan.

**The Modest Radius Rule.** Rounded corners soften dense clinical UI but never turn every surface into a pill.

## Components

### Buttons

- **Shape:** gently rounded controls (8px), with a 40px minimum height; icon-only buttons use a 44px square target.
- **Primary:** royal blue with white text and 8px × 16px internal padding. The sign-in action expands to full width, 62px on desktop and 54px on mobile, with a 10px radius.
- **Hover / Focus:** primary hover deepens to Pressed Royal Blue; all keyboard-focusable controls receive a visible 3px royal-blue outline with a 3px offset. Disabled controls retain their label and fall to 45% opacity.
- **Outline / Ghost:** outline buttons use a white surface and cool hairline; ghost buttons keep a transparent ground until hover, when they receive Selection Wash.

### Chips

- **Style:** compact text labels on pale tonal grounds, usually 4px × 8px padding with 5–7px corners; workflow preview chips may use a full pill.
- **State:** selected or scoped blue, verified green, caution amber, or coordination violet. Every semantic chip keeps a visible label.

### Cards / Containers

- **Corner Style:** operational cards use a 12px radius; larger feature canvases use 16px.
- **Background:** Working White on Pale Clinical Canvas; selected or informational regions use Selection Wash.
- **Shadow Strategy:** flat and bordered in authenticated workspaces; see Elevation & Depth for the two threshold exceptions.
- **Border:** a single Cool Hairline, with a pale blue border for selected or boundary information.
- **Internal Padding:** typically 20px for operational cards and 24–34px for feature canvases.

### Inputs / Fields

- **Style:** white 58px fields with a Field Blue Hairline, 10px corners, a leading Lucide icon, and 17px input text. Mobile fields reduce to 52px and 16px text while preserving a 44px password-toggle target.
- **Focus:** the border becomes royal blue with a soft 3px blue focus halo; the global focus outline remains visible on standalone controls.
- **Error / Disabled:** invalid fields use Clinical Error and retain field-specific error text. Request feedback appears in a labelled Error Wash notice; entered username remains intact after credential failure.

### Navigation

Desktop navigation uses 47–48px rows with 8px corners, navy-slate inactive labels, and Selection Wash with royal-blue text for the active destination. Tablet collapses copy into an 88px icon rail. Mobile uses a fixed white bottom bar with four primary destinations, visible labels, and 55px minimum rows. Disabled destinations remain visible but muted and non-interactive.

### Folded-document EPR Mark

The recurring mark is a compact royal-blue document tile with a white medical plus and a pale folded corner. Its standard shell size is 39 × 43px; sign-in variants scale it without changing its silhouette. Use it with the EPR wordmark and a plain-language workspace caption.

### Scope Boundary Notice

Security, tenant, and patient-record boundaries use a pale blue bordered panel with a shield icon, a bold first sentence, and a supporting explanation. The statement precedes feature summaries and never relies on colour alone.

## Do's and Don'ts

### Do:

- **Do** reuse royal blue for action, focus, active navigation, and explicit scope while keeping most of the screen neutral.
- **Do** place visible text beside status colour and Lucide icons, especially for security, connection, urgency, and availability.
- **Do** preserve the sidebar → rail → bottom-navigation progression and keep top-level context sticky.
- **Do** use fine borders and pale tonal fills to structure dense information before adding shadow.
- **Do** keep unavailable functionality visible only when its “Not connected” or disabled state is explicit.

### Don't:

- **Don't** place authenticated workspace content on decorative healthcare imagery, glass effects, or broad gradients.
- **Don't** use colour, an icon, or hover alone to communicate clinical or system meaning.
- **Don't** introduce a decorative typeface or mix icon families; use Roboto and Lucide consistently.
- **Don't** inflate routine cards into floating, heavily shadowed objects or use pill shapes for general containers.
- **Don't** let responsive compression hide scope, page identity, primary action, or labelled system state.
