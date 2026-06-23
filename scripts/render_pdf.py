#!/usr/bin/env python3
"""
gupiao-quant 通用 PDF 渲染脚本（实战选股 / 个股分析共用）
- 从 stdin 读 HTML 字符串
- 用本地 Chrome / Playwright chromium 渲染 A4 PDF
- 输出到命令行第一个参数指定路径

Chrome 路径按以下顺序尝试：
  1) 环境变量 PRACTICAL_SELECT_CHROME
  2) /Applications/Google Chrome.app/Contents/MacOS/Google Chrome (macOS)
  3) /usr/bin/google-chrome (Linux)
  4) Playwright 自带 chromium（fallback）
"""
import sys
import os
import platform
from pathlib import Path

CHROME_CANDIDATES = [
    os.environ.get("PRACTICAL_SELECT_CHROME"),
    "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
    "/Applications/Chromium.app/Contents/MacOS/Chromium",
    "/usr/bin/google-chrome",
    "/usr/bin/chromium-browser",
    "/usr/bin/chromium",
]


def find_chrome():
    for c in CHROME_CANDIDATES:
        if c and Path(c).exists():
            return c
    return None


def render_with_playwright(html, output_path, chrome_path=None):
    from playwright.sync_api import sync_playwright
    launch_kwargs = {
        "headless": True,
        "args": ["--no-sandbox", "--disable-dev-shm-usage"],
    }
    if chrome_path:
        launch_kwargs["executable_path"] = chrome_path
    with sync_playwright() as p:
        browser = p.chromium.launch(**launch_kwargs)
        try:
            page = browser.new_page()
            page.set_content(html, wait_until="load", timeout=30000)
            page.wait_for_timeout(500)  # 中文字体加载
            page.pdf(
                path=output_path,
                format="A4",
                print_background=True,
                prefer_css_page_size=True,
                margin={"top": "0", "bottom": "0", "left": "0", "right": "0"},
            )
        finally:
            browser.close()


def render_with_chrome_cli(html_path, output_path, chrome_path):
    """直接用 Chrome headless --print-to-pdf（无需 playwright）"""
    import subprocess
    cmd = [
        chrome_path,
        "--headless=new",
        "--no-sandbox",
        "--disable-gpu",
        "--no-pdf-header-footer",
        f"--print-to-pdf={output_path}",
        html_path,
    ]
    subprocess.run(cmd, check=True, timeout=60)


def main():
    if len(sys.argv) < 2:
        print("usage: render_pdf.py <output_path>", file=sys.stderr)
        sys.exit(1)
    output_path = os.path.abspath(sys.argv[1])
    html = sys.stdin.read()
    if not html.strip():
        print("HTML content is empty", file=sys.stderr)
        sys.exit(2)

    chrome_path = find_chrome()

    # 先写临时 HTML 文件，CLI 方式最稳
    tmp_html = output_path + ".tmp.html"
    with open(tmp_html, "w", encoding="utf-8") as f:
        f.write(html)

    try:
        if chrome_path:
            # 直接用 Chrome CLI，最稳，不依赖 playwright
            render_with_chrome_cli(tmp_html, output_path, chrome_path)
        else:
            # 退到 playwright（如果已经装好了）
            render_with_playwright(html, output_path)
    finally:
        try:
            os.remove(tmp_html)
        except Exception:
            pass

    size = os.path.getsize(output_path)
    chrome_used = chrome_path or "playwright-chromium"
    print(f"PDF generated via {chrome_used}: {output_path} ({size} bytes)", file=sys.stderr)


if __name__ == "__main__":
    main()