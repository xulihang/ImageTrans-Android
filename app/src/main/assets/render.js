/*
 * ImageTrans WebView renderer.
 *
 * This is the text-layout/rasterisation part of the Chrome extension's
 * getImage.js, extracted so the Android app can keep using the same rendering
 * logic. All OCR and translation happens natively; this page only receives
 * the source image plus text boxes and returns the rendered image as a data
 * URL.
 */
(function () {
    'use strict';

    var DEFAULT_OPTIONS = {
        renderMode: 'dom',              // 'dom' | 'canvas'
        renderTextCSS: 'text-align: center;\nborder-radius: 10%;',
        minFontSize: 14,
        // Multiplier for the fitted font size (1 = as calculated).
        fontScale: 1,
        textRepairMode: 'white',        // 'white' | 'background' | 'none'
        sourceLang: 'auto',
        targetLang: 'zh'
    };

    function log() {
        if (window.console && console.log) {
            console.log.apply(console, arguments);
        }
    }

    function parseFontCSS(cssText) {
        var style = {
            fontFamily: 'sans-serif',
            fontWeight: '',
            fontStyle: '',
            color: '#000000',
            textAlign: 'left',
            textTransform: '',
            backgroundColor: '#FFFFFF',
            borderRadius: { value: 0, unit: 'px' },
            strokeColor: '#FFFFFF',
            strokeWidth: null,
            textShadows: []
        };
        if (!cssText) return style;
        var rules = String(cssText).split(';').map(function (s) { return s.trim(); }).filter(Boolean);
        for (var i = 0; i < rules.length; i++) {
            var rule = rules[i];
            var colonIdx = rule.indexOf(':');
            if (colonIdx === -1) continue;
            var prop = rule.substring(0, colonIdx).trim().toLowerCase();
            var val = rule.substring(colonIdx + 1).trim();
            switch (prop) {
                case 'font-family': style.fontFamily = val; break;
                case 'font-weight': style.fontWeight = val; break;
                case 'font-style': style.fontStyle = val; break;
                case 'color': style.color = val; break;
                case 'text-align':
                    if (['left', 'center', 'right'].indexOf(val) !== -1) style.textAlign = val;
                    break;
                case 'text-transform': style.textTransform = val; break;
                case 'background-color': style.backgroundColor = val; break;
                case 'border-radius':
                    if (val.charAt(val.length - 1) === '%') {
                        var pct = parseFloat(val);
                        if (pct > 0) style.borderRadius = { value: pct, unit: '%' };
                    } else {
                        var px = parseFloat(val);
                        if (px > 0) style.borderRadius = { value: px, unit: 'px' };
                    }
                    break;
                case '-webkit-text-stroke-color': style.strokeColor = val; break;
                case '-webkit-text-stroke-width': style.strokeWidth = parseFloat(val) || null; break;
                case 'text-shadow': style.textShadows = parseTextShadows(val); break;
            }
        }
        return style;
    }

    function parseTextShadows(str) {
        var list = [];
        var parts = String(str).split(',').map(function (s) { return s.trim(); }).filter(Boolean);
        for (var i = 0; i < parts.length; i++) {
            var tokens = parts[i].split(/\s+/).filter(Boolean);
            var color = null;
            var nums = [];
            for (var j = 0; j < tokens.length; j++) {
                var tok = tokens[j];
                if (color === null && /^[#a-zA-Z]/.test(tok) && isNaN(parseFloat(tok))) {
                    color = tok;
                } else if (!isNaN(parseFloat(tok))) {
                    nums.push(parseFloat(tok));
                } else if (color === null) {
                    color = tok;
                }
            }
            list.push({
                dx: nums[0] || 0,
                dy: nums[1] || 0,
                blur: nums[2] || 0,
                color: color || '#000000'
            });
        }
        return list;
    }

    function buildFontString(fontSize, style) {
        var parts = [];
        if (style.fontStyle) parts.push(style.fontStyle);
        if (style.fontWeight) parts.push(style.fontWeight);
        parts.push(fontSize + 'px');
        parts.push(style.fontFamily);
        return parts.join(' ');
    }

    function applyTextTransform(text, transform) {
        switch (transform) {
            case 'uppercase': return text.toUpperCase();
            case 'lowercase': return text.toLowerCase();
            case 'capitalize': return text.replace(/\b\w/g, function (c) { return c.toUpperCase(); });
            default: return text;
        }
    }

    function importantCss(css) {
        return css.split(';').map(function (s) {
            s = s.trim();
            if (!s) return '';
            if (/^all\s*:/i.test(s)) return s;
            if (/!\s*important\s*$/i.test(s)) return s;
            return s + ' !important';
        }).join(';');
    }

    function detectBackgroundColor(img, box) {
        if (!img) return null;
        var w = img.naturalWidth || img.width;
        var h = img.naturalHeight || img.height;
        if (!w || !h) return null;
        var geo = box.geometry || {};
        var bx = Math.round(geo.X || geo.x || 0);
        var by = Math.round(geo.Y || geo.y || 0);
        var bw = Math.round(geo.width || geo.Width || 0);
        var bh = Math.round(geo.height || geo.Height || 0);
        if (bw <= 0 || bh <= 0) return null;

        var m = Math.max(4, Math.round(Math.min(bw, bh) * 0.15));
        var cw = Math.min(bw + 2 * m, w);
        var ch = Math.min(bh + 2 * m, h);
        if (cw <= 0 || ch <= 0) return null;

        var canvas = document.createElement('canvas');
        canvas.width = cw;
        canvas.height = ch;
        var ctx = canvas.getContext('2d', { willReadFrequently: true });
        var ox = Math.max(0, Math.min(bx - m, w - cw));
        var oy = Math.max(0, Math.min(by - m, h - ch));
        ctx.drawImage(img, ox, oy, cw, ch, 0, 0, cw, ch);
        var data;
        try {
            data = ctx.getImageData(0, 0, cw, ch).data;
        } catch (e) {
            return null;
        }

        var innerL = bx - ox;
        var innerR = innerL + bw;
        var innerT = by - oy;
        var innerB = innerT + bh;
        var pixels = [];

        function collect(atX, atY) {
            if (atX < 0 || atX >= cw || atY < 0 || atY >= ch) return;
            var idx = (atY * cw + atX) * 4;
            pixels.push(data[idx], data[idx + 1], data[idx + 2]);
        }

        for (var x = Math.max(0, innerL) + 1; x < Math.min(cw, innerR) - 1; x++) {
            collect(x, innerT - 1);
            collect(x, innerT - 2);
        }
        for (var x2 = Math.max(0, innerL) + 1; x2 < Math.min(cw, innerR) - 1; x2++) {
            collect(x2, innerB);
            collect(x2, innerB + 1);
        }
        for (var y = Math.max(0, innerT) + 1; y < Math.min(ch, innerB) - 1; y++) {
            collect(innerL - 1, y);
            collect(innerL - 2, y);
        }
        for (var y2 = Math.max(0, innerT) + 1; y2 < Math.min(ch, innerB) - 1; y2++) {
            collect(innerR, y2);
            collect(innerR + 1, y2);
        }

        if (pixels.length === 0) return null;
        return 'rgb(' + medianChannel(pixels, 0) + ',' + medianChannel(pixels, 1) + ',' + medianChannel(pixels, 2) + ')';
    }

    function medianChannel(buffer, offset) {
        var vals = [];
        for (var i = offset; i < buffer.length; i += 3) vals.push(buffer[i]);
        vals.sort(function (a, b) { return a - b; });
        return vals[Math.floor(vals.length / 2)];
    }

    function fillRoundRect(ctx, x, y, w, h, borderRadius) {
        var r = borderRadius.value;
        if (borderRadius.unit === '%') {
            r = (Math.min(w, h) * r) / 100;
        }
        if (r <= 0) {
            ctx.fillRect(x, y, w, h);
            return;
        }
        r = Math.min(r, w / 2, h / 2);
        ctx.beginPath();
        ctx.moveTo(x + r, y);
        ctx.lineTo(x + w - r, y);
        ctx.arcTo(x + w, y, x + w, y + r, r);
        ctx.lineTo(x + w, y + h - r);
        ctx.arcTo(x + w, y + h, x + w - r, y + h, r);
        ctx.lineTo(x + r, y + h);
        ctx.arcTo(x, y + h, x, y + h - r, r);
        ctx.lineTo(x, y + r);
        ctx.arcTo(x, y, x + r, y, r);
        ctx.closePath();
        ctx.fill();
    }

    function isRTLChar(cp) {
        return (cp >= 0x0590 && cp <= 0x05FF) ||
            (cp >= 0x0600 && cp <= 0x08FF) ||
            (cp >= 0xFB50 && cp <= 0xFDFF) ||
            (cp >= 0xFE70 && cp <= 0xFEFF);
    }

    function isLTRChar(ch) {
        return /[a-zA-ZÀ-˿Ͱ-ӿऀ-࿿Ḁ-῿⺀-퟿]/.test(ch);
    }

    function detectTextDirection(text) {
        var rtl = 0;
        var ltr = 0;
        for (var i = 0; i < text.length; i++) {
            var cp = text.codePointAt(i);
            if (cp > 0xFFFF) i++;
            if (isRTLChar(cp)) rtl++;
            else if (isLTRChar(text[i])) ltr++;
        }
        return rtl > ltr ? 'rtl' : 'ltr';
    }

    var RTL_LANGUAGES = ['ar', 'fa', 'he', 'ur', 'ps', 'sd', 'ug', 'yi', 'dv', 'ckb', 'prs', 'skr', 'bal'];

    function isRTLLanguage(lang) {
        if (!lang) return false;
        return RTL_LANGUAGES.indexOf(String(lang).toLowerCase().split(/[-_]/)[0]) !== -1;
    }

    function getRenderedTextLang(box, options) {
        var srcText = box.source || box.text || '';
        if (srcText && box.target === srcText) return options.sourceLang;
        return options.targetLang;
    }

    function getRenderedTextDirection(box, text, options) {
        if (isRTLLanguage(getRenderedTextLang(box, options))) return 'rtl';
        return detectTextDirection(text);
    }

    /**
     * Fits the font to the box, then applies options.fontScale. The scaled
     * size may overflow the box; the caller then grows the box to fit it.
     */
    function fitBoxFontSize(el, upperBound, minimum, fontScale) {
        var cw = el.clientWidth;
        var ch = el.clientHeight;
        var tol = 1;
        var min = (typeof minimum === 'number' && minimum > 0) ? minimum : 4;
        var lo = 4;
        var hi = Math.max(4, Math.min(upperBound || Math.min(ch, 200), 200));
        var best = lo;
        for (var i = 0; i < 12; i++) {
            var mid = (lo + hi) / 2;
            el.style.setProperty('font-size', mid + 'px', 'important');
            if (el.scrollWidth <= cw + tol && el.scrollHeight <= ch + tol) {
                best = mid;
                lo = mid;
            } else {
                hi = mid;
            }
            if (hi - lo < 0.5) break;
        }
        var fit = Math.floor(best);
        var scale = (typeof fontScale === 'number' && fontScale > 0) ? fontScale : 1;
        var used = Math.max(min, Math.round(fit * scale));
        el.style.setProperty('font-size', used + 'px', 'important');
        return { fit: fit, used: used };
    }

    function loadImageElement(source) {
        return new Promise(function (resolve, reject) {
            var img = new Image();
            img.onload = function () { resolve(img); };
            img.onerror = function () { reject(new Error('Failed to load image')); };
            if (String(source).indexOf('data:') === 0) {
                img.src = source;
            } else {
                img.src = 'data:image/jpeg;base64,' + source;
            }
        });
    }

    function clampBox(x, y, w, h, natW, natH) {
        x = Math.max(0, x);
        y = Math.max(0, y);
        w = Math.min(w, natW - x);
        h = Math.min(h, natH - y);
        return { x: x, y: y, w: w, h: h };
    }

    function renderTranslatedImageDOM(base64Image, boxes, options) {
        return loadImageElement(base64Image).then(function (img) {
            var natW = img.naturalWidth;
            var natH = img.naturalHeight;
            if (!natW || !natH) throw new Error('Invalid image dimensions');

            var wrapper = document.createElement('div');
            wrapper.style.cssText = importantCss(
                'all:initial;display:block;position:fixed;left:-99999px;top:0;' +
                'width:' + natW + 'px;height:' + natH + 'px;'
            );

            var container = document.createElement('div');
            container.style.cssText = importantCss(
                'all:initial;display:block;position:relative;left:0;top:0;' +
                'width:' + natW + 'px;height:' + natH + 'px;' +
                'background-color:transparent;'
            );

            var userCssHasDirection = /(^|[;])\s*direction\s*:/.test(options.renderTextCSS);
            var userFontSize = null;
            var fontMatch = options.renderTextCSS.match(/font-size\s*:\s*([^;]+)/i);
            if (fontMatch) {
                var m = fontMatch[1].trim().match(/^([\d.]+)\s*(px|rem|em)?$/);
                if (m) {
                    var n = parseFloat(m[1]);
                    userFontSize = (m[2] === 'em' || m[2] === 'rem') ? n * 16 : n;
                }
            }

            var textBaseCSS =
                'all:initial;position:absolute;display:block;box-sizing:border-box;margin:0;padding:2px;' +
                'overflow:hidden;line-height:1.3;color:#000;-webkit-text-fill-color:#000;' +
                'background-color:#fff;font-family:sans-serif;';

            wrapper.appendChild(container);
            (document.body || document.documentElement).appendChild(wrapper);

            // What each box needs after it is laid out: its size (the
            // element grows around a larger font), the font, and the mask's
            // colour.
            var placed = [];
            for (var i = 0; i < boxes.length; i++) {
                var box = boxes[i];
                var geo = box.geometry || {};
                var bx = geo.X || geo.x || 0;
                var by = geo.Y || geo.y || 0;
                var bw = geo.width || box.Width || 0;
                var bh = geo.height || box.Height || 0;
                var targetText = box.target || '';
                if (bw <= 0 || bh <= 0 || !targetText) continue;

                var c = clampBox(bx, by, bw, bh, natW, natH);
                if (c.w <= 0 || c.h <= 0) continue;

                var maskColor = '#fff';
                if (options.textRepairMode === 'background') {
                    var detected = detectBackgroundColor(img, box);
                    if (detected) maskColor = detected;
                }
                var el = document.createElement('div');
                el.style.cssText = importantCss(
                    textBaseCSS +
                    'left:' + c.x + 'px;top:' + c.y + 'px;' +
                    'width:' + c.w + 'px;height:' + c.h + 'px;' +
                    'background-color:transparent;' +
                    options.renderTextCSS
                );
                if (!userCssHasDirection) {
                    el.style.setProperty('direction', getRenderedTextDirection(box, targetText, options), 'important');
                }
                el.textContent = targetText;
                // A box at the edge of the image keeps its place (the text
                // keeps its alignment); a text larger than its box runs over
                // (the element grows to the text) rather than being cut at
                // the box's edge.
                el.style.setProperty('overflow', 'visible', 'important');
                container.appendChild(el);
                var piece = { el: el, x: c.x, y: c.y, w: c.w, h: c.h, color: maskColor, text: targetText };

                var sized = fitBoxFontSize(el, userFontSize, options.minFontSize,
                        options.fontScale);
                // Larger than the box either because of the minimum size or
                // because of fontScale: the text keeps its size and runs
                // over the neighbouring boxes, as it did in the app before
                // (the mask stays the box's), never pushed back in, which
                // would cut it in half.
                // The text stays in the page: html-to-image draws it (vertical
                // text, alignment and all) on top of the masks filled in
                // below.
                el.setAttribute('data-text', '');
                placed.push(piece);
            }

            // The masks are filled in here, on the image itself, not through
            // html-to-image: there a mask would cover the text next to it.
            // The texts are drawn afterwards, by html-to-image, with nothing
            // covering them.
            var out = document.createElement('canvas');
            out.width = natW;
            out.height = natH;
            var ctx = out.getContext('2d');
            ctx.drawImage(img, 0, 0, natW, natH);
            for (var m = 0; m < placed.length; m++) {
                var p = placed[m];
                if (options.textRepairMode === 'none') continue;
                // Whole pixels, rounded out, so the text above covers it.
                var mx = Math.floor(p.x);
                var my = Math.floor(p.y);
                ctx.fillStyle = p.color;
                ctx.fillRect(mx, my, Math.ceil(p.x + p.w) - mx, Math.ceil(p.y + p.h) - my);
            }
            return htmlToImage.toCanvas(container, {
                backgroundColor: 'transparent',
                width: natW,
                height: natH,
                canvasWidth: natW,
                canvasHeight: natH,
                skipFonts: true,
                pixelRatio: 1
            }).then(function (textCanvas) {
                ctx.drawImage(textCanvas, 0, 0, natW, natH);
                wrapper.remove();
                return out.toDataURL('image/webp', 0.8);
            }).catch(function (err) {
                wrapper.remove();
                throw err;
            });
        });
    }

    function calcFontSize(ctx, text, maxWidth, maxHeight, textStyle, minFontSize, fontScale) {
        var padding = 2;
        var availWidth = maxWidth - padding * 2;
        var availHeight = maxHeight - padding * 2;
        if (availWidth <= 0 || availHeight <= 0) return 16;

        var lineHeightRatio = 1.3;
        var lo = 16;
        var hi = Math.min(availHeight, 200);
        var bestSize = lo;
        for (var i = 0; i < 15; i++) {
            var mid = (lo + hi) / 2;
            ctx.font = buildFontString(mid, textStyle);
            var lines = wrapLines(ctx, text, availWidth);
            var totalHeight = lines.length * mid * lineHeightRatio;
            if (totalHeight <= availHeight) {
                bestSize = mid;
                lo = mid;
            } else {
                hi = mid;
            }
            if (hi - lo < 1) break;
        }
        var fit = Math.floor(bestSize);
        var scale = (typeof fontScale === 'number' && fontScale > 0) ? fontScale : 1;
        return Math.max((typeof minFontSize === 'number' && minFontSize > 0) ? minFontSize : 4,
                Math.round(fit * scale));
    }

    function isCJK(ch) {
        var cp = ch.codePointAt(0);
        return (cp >= 0x4E00 && cp <= 0x9FFF) ||
            (cp >= 0x3400 && cp <= 0x4DBF) ||
            (cp >= 0x3040 && cp <= 0x309F) ||
            (cp >= 0x30A0 && cp <= 0x30FF) ||
            (cp >= 0xAC00 && cp <= 0xD7AF) ||
            (cp >= 0x3000 && cp <= 0x303F) ||
            (cp >= 0xFF00 && cp <= 0xFFEF);
    }

    function tokenizeForWrap(text) {
        var tokens = [];
        var i = 0;
        while (i < text.length) {
            var ch = text[i];
            if (isCJK(ch)) {
                tokens.push(ch);
                i++;
            } else if (/\s/.test(ch)) {
                var space = '';
                while (i < text.length && /\s/.test(text[i])) {
                    space += text[i];
                    i++;
                }
                tokens.push(space);
            } else {
                var seq = '';
                while (i < text.length && !isCJK(text[i]) && !/\s/.test(text[i])) {
                    seq += text[i];
                    i++;
                }
                tokens.push(seq);
            }
        }
        return tokens;
    }

    function wrapLines(ctx, text, maxWidth) {
        var lines = [];
        var tokens = tokenizeForWrap(text);
        var hasSpaces = /\s/.test(text);
        var line = '';
        for (var i = 0; i < tokens.length; i++) {
            var token = tokens[i];
            var testLine = line + token;
            if (ctx.measureText(testLine).width > maxWidth) {
                if (line.length > 0) {
                    lines.push(line);
                    line = hasSpaces ? token.trimStart() : token;
                } else {
                    for (var j = 0; j < token.length; j++) {
                        var ch = token[j];
                        var chTest = line + ch;
                        if (ctx.measureText(chTest).width > maxWidth && line.length > 0) {
                            lines.push(line);
                            line = ch;
                        } else {
                            line = chTest;
                        }
                    }
                }
            } else {
                line = testLine;
            }
        }
        if (line.length > 0) lines.push(line);
        return lines;
    }

    function drawTextBox(ctx, text, x, y, maxWidth, maxHeight, fontSize, textStyle) {
        var padding = 2;
        var availWidth = maxWidth - padding * 2;
        ctx.font = buildFontString(fontSize, textStyle);
        if (textStyle.strokeWidth === null) {
            ctx.lineWidth = Math.max(2, fontSize * 0.15);
        }
        var lineHeight = fontSize * 1.3;
        var lines = wrapLines(ctx, text, availWidth);
        var lineY = y + padding;
        for (var i = 0; i < lines.length; i++) {
            var lineX = x + padding;
            if (textStyle.textAlign === 'center') {
                lineX = x + maxWidth / 2;
                ctx.textAlign = 'center';
            } else if (textStyle.textAlign === 'right') {
                lineX = x + maxWidth - padding;
                ctx.textAlign = 'right';
            } else {
                ctx.textAlign = 'left';
            }
            for (var si = 0; si < textStyle.textShadows.length; si++) {
                var s = textStyle.textShadows[si];
                ctx.save();
                if (s.blur > 0) {
                    ctx.fillStyle = s.color;
                    ctx.shadowColor = s.color;
                    ctx.shadowBlur = s.blur;
                    ctx.shadowOffsetX = s.dx;
                    ctx.shadowOffsetY = s.dy;
                    ctx.fillText(lines[i], lineX, lineY);
                } else {
                    ctx.fillStyle = s.color;
                    ctx.translate(s.dx, s.dy);
                    ctx.fillText(lines[i], lineX, lineY);
                }
                ctx.restore();
            }
            ctx.strokeText(lines[i], lineX, lineY);
            ctx.fillStyle = textStyle.color;
            ctx.fillText(lines[i], lineX, lineY);
            lineY += lineHeight;
        }
    }

    function renderTranslatedImageCanvas(base64Image, boxes, options) {
        var textStyle = parseFontCSS(options.renderTextCSS);
        return loadImageElement(base64Image).then(function (img) {
            var c = document.createElement('canvas');
            c.width = img.naturalWidth;
            c.height = img.naturalHeight;
            var ctx = c.getContext('2d');
            ctx.drawImage(img, 0, 0);

            function clamp(x, y, w, h) {
                return clampBox(x, y, w, h, c.width, c.height);
            }

            for (var i = 0; i < boxes.length; i++) {
                var box = boxes[i];
                var geo = box.geometry || {};
                var bx = geo.X || geo.x || 0;
                var by = geo.Y || geo.y || 0;
                var bw = geo.width || geo.Width || 0;
                var bh = geo.height || geo.Height || 0;
                var targetText = box.target || '';
                if (bw <= 0 || bh <= 0 || !targetText) continue;

                var c2 = clamp(bx, by, bw, bh);
                var displayText = applyTextTransform(targetText, textStyle.textTransform);
                var fontSize = calcFontSize(ctx, displayText, c2.w, c2.h, textStyle,
                        options.minFontSize, options.fontScale);
                ctx.font = buildFontString(fontSize, textStyle);

                var textLines = wrapLines(ctx, displayText, c2.w - 4);
                var lineHeight = fontSize * 1.3;
                var blockW = c2.w;
                var blockH = (textLines.length - 1) * lineHeight + fontSize;
                var bw2 = blockW;
                var bh2 = Math.max(c2.h, blockH);

                var tx = c2.x;
                var ty = c2.y;
                if (ty + bh2 > c.height) ty = Math.max(0, c.height - bh2);
                var maxLineW = 0;
                for (var li = 0; li < textLines.length; li++) {
                    var w = ctx.measureText(textLines[li]).width;
                    if (w > maxLineW) maxLineW = w;
                }
                if (maxLineW + 4 > c.width) {
                    maxLineW = c.width - 4;
                    bw2 = c.width;
                } else if (maxLineW + 4 > bw2) {
                    bw2 = maxLineW + 4;
                }
                if (textStyle.textAlign === 'center') {
                    if (tx + bw2 > c.width) tx = Math.max(0, c.width - bw2);
                    if (tx < 0) tx = 0;
                } else {
                    if (tx + bw2 > c.width) tx = Math.max(0, c.width - bw2);
                }

                var fillColor = textStyle.backgroundColor;
                if (options.textRepairMode === 'background') {
                    var detected = detectBackgroundColor(img, box);
                    if (detected) fillColor = detected;
                }
                if (options.textRepairMode !== 'none') {
                    ctx.fillStyle = fillColor;
                    fillRoundRect(ctx, tx, ty, bw2, bh2, textStyle.borderRadius);
                }

                ctx.fillStyle = textStyle.color;
                ctx.textBaseline = 'top';
                ctx.strokeStyle = textStyle.strokeColor;
                if (textStyle.strokeWidth !== null) {
                    ctx.lineWidth = textStyle.strokeWidth;
                }
                drawTextBox(ctx, displayText, tx, ty, bw2, bh2, fontSize, textStyle);
            }

            return c.toDataURL('image/webp', 0.8);
        });
    }

    function normalizeBoxes(boxes) {
        if (!Array.isArray(boxes)) return [];
        return boxes.filter(function (box) {
            return box && box.geometry;
        });
    }

    function renderTranslatedImage(base64Image, boxes, options) {
        boxes = normalizeBoxes(boxes);
        if (options.renderMode === 'canvas') {
            return renderTranslatedImageCanvas(base64Image, boxes, options);
        }
        if (typeof htmlToImage === 'undefined' || !htmlToImage.toCanvas) {
            return renderTranslatedImageCanvas(base64Image, boxes, options);
        }
        return renderTranslatedImageDOM(base64Image, boxes, options).catch(function (err) {
            log('[ImageTrans] DOM rendering failed, falling back to canvas:', err);
            return renderTranslatedImageCanvas(base64Image, boxes, options);
        });
    }

    function mergeOptions(given) {
        var options = {};
        for (var key in DEFAULT_OPTIONS) {
            if (Object.prototype.hasOwnProperty.call(DEFAULT_OPTIONS, key)) {
                options[key] = DEFAULT_OPTIONS[key];
            }
        }
        if (given && typeof given === 'object') {
            for (var k in given) {
                if (Object.prototype.hasOwnProperty.call(given, k)) options[k] = given[k];
            }
        }
        return options;
    }

    function postResult(dataUrl) {
        if (window.AndroidRenderer && AndroidRenderer.onRenderResult) {
            AndroidRenderer.onRenderResult(dataUrl);
        }
    }

    function postError(message) {
        if (window.AndroidRenderer && AndroidRenderer.onRenderError) {
            AndroidRenderer.onRenderError(String(message || 'Unknown rendering error'));
        }
    }

    window.renderTranslation = function (payload) {
        try {
            if (!payload || !payload.image) {
                postError('Missing image');
                return;
            }
            var options = mergeOptions(payload.options);
            var wait = (document.fonts && document.fonts.ready) ? document.fonts.ready : Promise.resolve();
            wait.then(function () {
                return renderTranslatedImage(payload.image, payload.boxes, options);
            }).then(function (dataUrl) {
                postResult(dataUrl);
            }).catch(function (err) {
                log('[ImageTrans] render failed:', err);
                postError(err && err.message ? err.message : err);
            });
        } catch (e) {
            postError(e && e.message ? e.message : e);
        }
    };
})();
