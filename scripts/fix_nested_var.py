#!/usr/bin/env python3
"""
清理 CSS 里 `var(--xxx, rgba(...))` 这种无效的嵌套 var() 写法，
把 fallback 去掉，直接用 `var(--xxx)`。skin.css 已经提供了所有 token。
"""
import re
from pathlib import Path

# 匹配 var(--name, <fallback>) 形式，fallback 里嵌套了 var() / rgba(...) / #hex
# 我们把整个 var(--name, fallback) 替换成 var(--name)
# 同时把 self-reference 的 var(--name, var(--name, value)) 改成 var(--name, value)
PATTERN = re.compile(
    r'var\(\s*--[a-zA-Z0-9_-]+\s*,\s*'
    r'(?:var\(\s*--[a-zA-Z0-9_-]+(?:\s*,\s*[^)]*)?\s*\)|'
    r'rgba?\([^)]*\)|'
    r'#[0-9a-fA-F]{3,8})\s*\)'
)

def fix(content):
    new = PATTERN.sub(lambda m: re.sub(r',\s*var\([^)]*\)\s*|,\s*rgba?\([^)]*\)\s*|,\s*#[0-9a-fA-F]{3,8}', '', m.group(0)), content)
    return new

def main():
    css_dir = Path('src/main/resources/static/css')
    total = 0
    for f in sorted(css_dir.glob('*.css')):
        content = f.read_text(encoding='utf-8')
        new = fix(content)
        if new != content:
            count = len(PATTERN.findall(content))
            f.write_text(new, encoding='utf-8')
            print(f'✓ {f.name}: {count} 处清理')
            total += count
    print(f'\n总计 {total} 处清理')

if __name__ == '__main__':
    main()
