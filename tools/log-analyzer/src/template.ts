export function html() {
  return `<!DOCTYPE html>
<html lang="en" class="dark">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <title>Spore</title>
  <link rel="preconnect" href="https://fonts.googleapis.com" />
  <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin />
  <link href="https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700;800;900&family=JetBrains+Mono:wght@400;500;600&display=swap" rel="stylesheet" />
  <link rel="stylesheet" href="/public/styles.css" />
  <script>
    // Restore theme before paint to avoid flash
    // Must match blazecn theme store keys
    (function() {
      var h = document.documentElement;
      var dark = localStorage.getItem('blazecn_dark_mode');
      if (dark === 'false') h.classList.remove('dark');
      else if (dark === 'true') h.classList.add('dark');
      else if (!window.matchMedia('(prefers-color-scheme: dark)').matches) h.classList.remove('dark');
      var theme = localStorage.getItem('blazecn_base_theme');
      if (theme && theme !== 'neutral') h.classList.add('theme-' + theme);
    })();
  </script>
</head>
<body>
  <div id="app"></div>
  <script type="module" src="/public/dist/entry.js"></script>
</body>
</html>`;
}
