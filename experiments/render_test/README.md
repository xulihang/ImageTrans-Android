# Renderer test page

`index.html` draws a page with old text behind, renders the same boxes the app
would (once with three plain boxes, once with overlapping ones), and shows the
result next to the source, so the text and the masks can be checked against
each other, in a browser rather than only on the phone.

Open it directly from the file system; it needs no server:

    file:///<path to the repo>/experiments/render_test/index.html

Pick the CSS (border radius, bold with a stroke, vertical text), the text
alignment and the background mode, then press "渲染形状＋重叠页" (render the
shapes and the overlapping page). `html-to-image.js` and `render.js` here are
copies of the app's own renderer files; copy them over again after changing
`app/src/main/assets/`.

## The problems it showed

### A text that lies beside an opaque block is dropped

html-to-image turns the element into an SVG `<foreignObject>` and lets the
browser draw it. With one box painted over another in the same element, a
text next to an opaque box came out missing, and a box painted after a text
covered that text. Two layers are drawn separately instead: the masks (the
backgrounds) first, the texts after, and the texts are put on last, so a text
is never under a mask. Each layer is taken on its own, alone on the page: in
the page html-to-image also draws the other layer's texts.

### A layer of absolutely positioned boxes measures as nothing

The texts and the masks are all `position: absolute`. A wrapper holding only
those has no height of its own, and html-to-image measures the element it is
given: the layer came out as a single point. Each layer is now put in a box
with the image's width and height, and the box is put at the page's top left
corner for the shot — html-to-image draws the element as it stands among the
page's others, and a box further down was taken for a blank image — then
taken out again.

### The page lays text out differently from the canvas renderer

Fitting the font by the page (`scrollWidth`/`scrollHeight`) did not agree with
the canvas renderer:

- with `all:initial` in the box's CSS, Chrome measures the text in a fallback
  font, wider or narrower than the one finally used;
- with `border-radius: 50%`, the scroll sizes only report the rectangle past
  the rounded corners, so the largest font looked as if it fitted;
- a box added to a page that has not been laid out yet reports sizes of 0.

The DOM renderer now takes the font size from `calcFontSize()`, the canvas
renderer's own function, and the page only lays the text out.

### The rounding cut the text

`border-radius` clips what lies outside the rounded box, so a text set to the
box's full width lost its corners. The rounding belongs to the mask: the
text's own box is a plain rectangle (with `overflow: visible`), and only the
mask is rounded.

## Looking at the layers

The page sets `window.__keepLayers = true`: with it the script leaves the
two layer boxes on the page instead of taking them out after the shot. The
inspector then shows, one after another, a `[data-layer-box="texts"]` with
each text box as it is drawn and a `[data-layer-box="masks"]` with each mask;
their styles can be edited there. Without the flag (the app) the boxes are
taken out again once drawn.

`index.html` also sets `renderMode: 'dom'`. With `'canvas'` the app's older
renderer draws everything, boxes and text, on one canvas: it is what the DOM
renderer was made to agree with, and what it falls back to on an error.
