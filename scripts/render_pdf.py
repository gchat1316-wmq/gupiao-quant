#!/usr/bin/env python3
"""
gupiao-quant 个股分析 PDF 渲染脚本
- 从 stdin 读 HTML 字符串
- 用 Playwright headless chromium 渲染 A4 PDF
- 输出到命令行第一个参数指定路径
"""
import sys
import os
from playwright.sync_api import sync_playwright


def main():
    if len(sys.argv) < 2:
        print("usage: render_pdf.py <output_path>", file=sys.stderr)
        sys.exit(1)

    output_path = os.path.abspath(sys.argv[1])
    html = sys.stdin.read()

    if not html.strip():
        print("HTML content is empty", file=sys.stderr)
        sys.exit(2)

    with sync_playwright() as p:
        browser = p.chromium.launch(
            headless=True,
            args=['--no-sandbox', '--disable-dev-shm-usage']
        )
        try:
            page = browser.new_page()
            page.set_content(html, wait_until='load', timeout=30000)
            # 字体加载完成 (中文字体)
            page.wait_for_timeout(300)
            page.pdf(
                path=output_path,
                format='A4',
                print_background=True,
                prefer_css_page_size=True,
                margin={'top': '0', 'bottom': '0', 'left': '0', 'right': '0'}
            )
        finally:
            browser.close()

    size = os.path.getsize(output_path)
    print(f"PDF generated: {output_path} ({size} bytes)", file=sys.stderr)


if __name__ == "__main__":
    main()
