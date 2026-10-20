/**
 * 封面取色（Service Worker 与弹出页共用）
 *
 * 把封面缩到 32×18 后按"饱和度 × 适中亮度"加权统计色相直方图，取最有代表性、又足够鲜明的颜色，
 * 再把亮度/饱和度规整到适合做光晕与进度条的范围，避免灰暗封面得到脏色、纯白封面得到看不见的颜色。
 */
(function (global) {
  'use strict';

  function rgbToHsl(r, g, b) {
    r /= 255; g /= 255; b /= 255;
    const max = Math.max(r, g, b), min = Math.min(r, g, b);
    let h = 0, s = 0;
    const l = (max + min) / 2;
    if (max !== min) {
      const d = max - min;
      s = l > 0.5 ? d / (2 - max - min) : d / (max + min);
      if (max === r) h = (g - b) / d + (g < b ? 6 : 0);
      else if (max === g) h = (b - r) / d + 2;
      else h = (r - g) / d + 4;
      h *= 60;
    }
    return [h, s, l];
  }

  /** @param {Uint8ClampedArray} data RGBA 像素 */
  function dominant(data) {
    const bins = new Array(36).fill(0).map(() => ({ w: 0, h: 0, s: 0, l: 0 }));
    let total = 0;
    for (let i = 0; i < data.length; i += 4) {
      if (data[i + 3] < 128) continue;
      const [h, s, l] = rgbToHsl(data[i], data[i + 1], data[i + 2]);
      // 偏好鲜明、亮度适中的像素
      const w = s * s * (1 - Math.abs(l - 0.5) * 1.6) + 0.002;
      if (w <= 0) continue;
      const b = bins[Math.floor(h / 10) % 36];
      b.w += w; b.h += h * w; b.s += s * w; b.l += l * w;
      total += w;
    }
    if (!total) return null;
    // 相邻 3 个色相桶平滑后取峰值
    let best = 0, bestW = -1;
    for (let i = 0; i < 36; i++) {
      const w = bins[(i + 35) % 36].w * 0.5 + bins[i].w + bins[(i + 1) % 36].w * 0.5;
      if (w > bestW) { bestW = w; best = i; }
    }
    const b = bins[best];
    if (!b.w) return null;
    return { h: b.h / b.w, s: b.s / b.w, l: b.l / b.w };
  }

  /** 把颜色规整到可用范围，返回 { h, s, l }（s、l 为百分数） */
  function normalize(c) {
    if (!c) return null;
    const s = Math.round(Math.min(78, Math.max(42, c.s * 100 * 1.15)));
    const l = Math.round(Math.min(58, Math.max(46, c.l * 100)));
    return { h: Math.round(c.h), s, l };
  }

  async function fromBlob(blob) {
    const bmp = await createImageBitmap(blob, { resizeWidth: 32, resizeHeight: 18, resizeQuality: 'medium' });
    const canvas = new OffscreenCanvas(32, 18);
    const ctx = canvas.getContext('2d', { willReadFrequently: true });
    ctx.drawImage(bmp, 0, 0, 32, 18);
    if (bmp.close) bmp.close();
    return normalize(dominant(ctx.getImageData(0, 0, 32, 18).data));
  }

  const cache = new Map();
  /** 取封面主色；失败返回 null */
  function fromUrl(url) {
    if (!url) return Promise.resolve(null);
    if (!cache.has(url)) {
      cache.set(url, fetch(url, { credentials: 'omit' })
        .then((r) => (r.ok ? r.blob() : null))
        .then((b) => (b ? fromBlob(b) : null))
        .catch(() => null));
    }
    return cache.get(url);
  }

  const css = (c, a = 1, dl = 0) => (c ? `hsl(${c.h} ${c.s}% ${Math.min(90, Math.max(10, c.l + dl))}% / ${a})` : '');

  global.CoverColor = { fromUrl, fromBlob, dominant, normalize, css };
})(typeof self !== 'undefined' ? self : globalThis);
