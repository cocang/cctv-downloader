// 在样式加载前应用外观，避免打开弹窗时闪烁
(function () {
  try {
    var t = localStorage.getItem('theme') || 'auto';
    document.documentElement.dataset.theme = t;
  } catch (e) {}
})();
