#!/usr/bin/env python3
"""
清全站 CSS 硬编码颜色 → token。

策略：把硬编码的"品牌色/主题色 hex"替换成对应的 CSS 变量。
中性色（白/灰/淡彩）和品牌识别色（橘黄/绿/红警示）按情况处理：
  - 同色相的"主色 / hover / soft / faint / border / text" 映射到 token
  - 不映射的色直接保留

映射表是从 skin.css 倒推出来的 —— 所有出现过的 token 值都对应到主题。
"""
import re
import sys
from pathlib import Path

# 颜色映射表（小写 hex → token）
# 每一项都是"硬编码值 → CSS 变量名"
# 注意：保留警告色（warn 黄/橙）和状态绿（绿涨/绿跌）等"语义固定"的色
COLOR_MAP = {
    # ===== 玫红主色（var(--base) 候选） =====
    '#e1062c': 'var(--base)',
    '#d90028': 'var(--base)',
    '#d90b31': 'var(--base)',
    '#d63950': 'var(--base)',         # 牛市红主色

    # 深红（var(--base-deep) / var(--base-text)）
    '#a9001f': 'var(--base-deep)',
    '#6f071c': 'var(--base-deep)',
    '#7a0017': 'var(--base-text)',
    '#9f1239': 'var(--accent-text)',
    '#5f2733': 'var(--accent-text)',

    # 亮红（常用于渐变起色 / hover）
    '#ff2a49': 'var(--base)',
    '#ff3257': 'var(--base)',
    '#ff3654': 'var(--base)',

    # 极深红（渐变终点）
    '#b50025': 'var(--base-deep)',
    '#ba001f': 'var(--base-deep)',
    '#4c0613': 'var(--base-deep)',
    '#8f001b': 'var(--base-deep)',
    '#730016': 'var(--base-deep)',

    # 浅红（var(--base-soft) / var(--base-faint) / var(--base-border)）
    '#fff0f3': 'var(--base-soft)',
    '#fceff2': 'var(--base-soft)',
    '#fff5f5': 'var(--base-soft)',
    '#fff7f9': 'var(--base-faint)',
    '#fff1f2': 'var(--base-faint)',
    '#fff1f3': 'var(--base-faint)',
    '#fecaca': 'var(--base-border)',
    '#fecdd3': 'var(--base-border)',
    '#fed7aa': 'var(--base-border)',
    '#fcd7c5': 'var(--base-border)',
    '#ffb3c1': 'var(--base-border)',
    '#f3c4cd': 'var(--base-border)',
    '#ffe4e6': 'var(--base-faint)',

    # ===== 蓝色（科技蓝/var(--base)） =====
    '#1e88ff': 'var(--accent)',
    '#2563eb': 'var(--base)',
    '#1d4ed8': 'var(--base-hover)',
    '#1e3a8a': 'var(--base-deep)',
    '#1e40af': 'var(--base-text)',
    '#3b82f6': 'var(--base)',
    '#4f8cff': 'var(--base)',
    '#6ca5ff': 'var(--base)',
    '#4d7df0': 'var(--base)',
    '#315cd2': 'var(--base-deep)',

    # 浅蓝 / 蓝边
    '#eff6ff': 'var(--base-soft)',
    '#dbeafe': 'var(--base-soft)',
    '#c7d7f7': 'var(--base-border)',
    '#e0e7ff': 'var(--accent-soft)',

    # ===== 绿色（跌 / down） =====
    '#16a34a': 'var(--down)',
    '#15803d': 'var(--down)',
    '#22c55e': 'var(--down)',
    '#1a7a4a': 'var(--down)',          # 旧 invest-green-high
    '#52c41a': 'var(--down)',          # 旧 invest-green-mid
    '#2f9e44': 'var(--down)',
    '#dcfce7': 'var(--down-soft, #dcfce7)',
    '#f0fdf4': 'var(--down-soft, #f0fdf4)',
    '#bbf7d0': 'var(--down-border, #bbf7d0)',

    # ===== 涨色 / 错误红 =====
    '#dc2626': 'var(--up)',
    '#b91c1c': 'var(--up)',
    '#be123c': 'var(--up)',
    '#ef4444': 'var(--up)',
    '#fa5252': 'var(--up)',            # 旧涨色
    '#e11d48': 'var(--up)',
    '#c0392b': 'var(--up)',

    # ===== 警告色（warn 保留，状态色） =====
    # 保持 hex 不变（语义固定），如果想跟主题再调，可以映射
    # 但本轮不动

    # ===== 边框 / 浅灰 =====
    '#eef1f5': 'var(--border)',
    '#eef2f7': 'var(--border)',
    '#e7eaf0': 'var(--border)',
    '#e7dfe4': 'var(--border)',
    '#eadfe4': 'var(--border)',
    '#e5e7eb': 'var(--border)',
    '#d1d5db': 'var(--border)',
    '#dce4ee': 'var(--border)',
    '#edf0f5': 'var(--border)',

    # ===== 文字色 =====
    '#9aa4b2': 'var(--text-soft)',
    '#94a3b8': 'var(--text-soft)',
    '#6b7280': 'var(--text-mid)',
    '#4b5563': 'var(--text-mid)',
    '#475569': 'var(--text-mid)',
    '#374151': 'var(--text-mid)',
    '#526072': 'var(--text-mid)',
    '#64748b': 'var(--text-mid)',
    '#7b8798': 'var(--text-mid)',
    '#1f2937': 'var(--text)',
    '#111827': 'var(--text)',
    '#0f172a': 'var(--text)',
    '#161b2b': 'var(--text)',
    '#152033': 'var(--text)',
    '#172033': 'var(--text)',
    '#9ca3af': 'var(--text-soft)',

    # ===== 背景色（保留 #fff 浅色系，#fafbfc 等映射到 --bg） =====
    '#fbfbfe': 'var(--bg)',
    '#fcfcfd': 'var(--bg)',
    '#f6f7fb': 'var(--bg)',
    '#f6f7f9': 'var(--bg)',
    '#f6f8fa': 'var(--bg)',
    '#f5f7fb': 'var(--bg)',
    '#f3f4f6': 'var(--bg)',
    '#fafbfc': 'var(--bg)',
    '#f1f3f5': 'var(--bg)',
    '#f1faf2': 'var(--bg)',
    '#eef0f3': 'var(--bg)',
    '#e9ecef': 'var(--bg)',
    '#f8fafc': 'var(--bg)',

    # ===== 透明色 / 阴影 rgba =====
    'rgba(225, 6, 44, 0.28)': 'var(--base-glow)',
    'rgba(225, 6, 44, 0.22)': 'var(--base-glow)',
    'rgba(225, 6, 44, 0.20)': 'var(--base-glow)',
    'rgba(225, 6, 44, 0.18)': 'var(--base-glow)',
    'rgba(225, 6, 44, 0.13)': 'var(--base-glow)',
    'rgba(225, 6, 44, 0.12)': 'var(--base-glow)',
    'rgba(225, 6, 44, 0.10)': 'var(--base-glow)',
    'rgba(225, 6, 44, 0.08)': 'var(--base-glow)',
    'rgba(225, 6, 44, 0.06)': 'var(--base-glow)',
    'rgba(225, 6, 44, 0.05)': 'var(--base-glow)',
    'rgba(225, 6, 44, 0.04)': 'var(--base-glow)',
    'rgba(225, 6, 44, 0.32)': 'var(--base-glow)',

    'rgba(255, 42, 73, 0.96)': 'var(--base-glow-strong, rgba(255, 42, 73, 0.96))',
    'rgba(255, 42, 73, 0.80)': 'var(--base-glow-strong, rgba(255, 42, 73, 0.80))',
    'rgba(255, 42, 73, 0.32)': 'var(--base-glow)',
    'rgba(255, 42, 73, 0.28)': 'var(--base-glow)',
    'rgba(255, 42, 73, 0.20)': 'var(--base-glow)',
    'rgba(255, 42, 73, 0.18)': 'var(--base-glow)',
    'rgba(255, 42, 73, 0.13)': 'var(--base-glow)',
    'rgba(255, 42, 73, 0.11)': 'var(--base-glow)',
    'rgba(255, 42, 73, 0.10)': 'var(--base-glow)',
    'rgba(255, 42, 73, 0.08)': 'var(--base-glow)',
    'rgba(255, 42, 73, 0.06)': 'var(--base-glow)',

    'rgba(96, 165, 250, 0.12)': 'var(--accent-glow)',
    'rgba(0, 212, 255, 0.10)': 'var(--accent-glow)',
    'rgba(0, 212, 255, 0.12)': 'var(--accent-glow)',
    'rgba(0, 212, 255, 0.14)': 'var(--accent-glow)',

    'rgba(58, 98, 210, 0.28)': 'var(--accent-glow)',
    'rgba(58, 98, 210, 0.34)': 'var(--accent-glow)',

    'rgba(80, 0, 24, 0.18)': 'var(--base-glow)',
    'rgba(80, 0, 24, 0.10)': 'var(--base-glow)',
    'rgba(80, 0, 24, 0.08)': 'var(--base-glow)',
    'rgba(80, 0, 24, 0.06)': 'var(--base-glow)',
    'rgba(80, 0, 24, 0.05)': 'var(--base-glow)',
    'rgba(80, 0, 24, 0.04)': 'var(--base-glow)',

    'rgba(104, 8, 32, 0.10)': 'var(--base-glow)',
    'rgba(104, 8, 32, 0.06)': 'var(--base-glow)',
    'rgba(169, 0, 31, 0.12)': 'var(--base-glow)',
    'rgba(181, 0, 37, 0.28)': 'var(--base-glow)',
    'rgba(115, 17, 40, 0.12)': 'var(--base-glow)',
    'rgba(232, 113, 75, 0.10)': 'var(--base-glow)',
    'rgba(27, 94, 32, 0.25)': 'var(--down-glow, rgba(27, 94, 32, 0.25))',
    'rgba(22, 27, 43, 0.04)': 'var(--shadow-soft, rgba(22, 27, 43, 0.04))',
    'rgba(22, 27, 43, 0.98)': 'var(--top-nav-bg, rgba(22, 27, 43, 0.98))',
    'rgba(68, 5, 19, 0.97)': 'var(--top-nav-bg-mid, rgba(68, 5, 19, 0.97))',
    'rgba(255, 255, 255, 0.78)': 'var(--top-nav-text)',
    'rgba(255, 255, 255, 0.96)': 'var(--top-nav-text-strong)',
    'rgba(255, 255, 255, 0.62)': 'var(--top-nav-text-soft)',
    'rgba(255, 255, 255, 0.98)': 'var(--card-bg-strong)',
    'rgba(255, 255, 255, 0.94)': 'var(--card-bg-strong)',
    'rgba(255, 255, 255, 0.90)': 'var(--card-bg-strong)',
    'rgba(255, 255, 255, 0.88)': 'var(--card-bg-strong)',
    'rgba(255, 255, 255, 0.78)': 'var(--top-nav-text)',
    'rgba(255, 255, 255, 0.75)': 'var(--card-bg-strong)',
    'rgba(255, 255, 255, 0.14)': 'var(--top-nav-line-soft)',
    'rgba(255, 255, 255, 0.08)': 'var(--top-nav-hover)',
    'rgba(246, 248, 253, 0.96)': 'var(--card-bg-soft)',
    'rgba(251, 242, 245, 0.96)': 'var(--accent-faint-strong)',
    'rgba(191, 211, 237, 0.92)': 'var(--base-border-strong)',
    'rgba(184, 201, 224, 0.88)': 'var(--border-strong)',
    'rgba(168, 85, 247, 0.18)': 'var(--accent-glow)',
    'rgba(169, 0, 31, 0.14)': 'var(--base-glow)',
    'rgba(169, 0, 31, 0.08)': 'var(--base-glow)',
    'rgba(181, 0, 37, 0.24)': 'var(--base-glow)',
    'rgba(225, 6, 44, 0.42)': 'var(--base-glow-strong)',
    'rgba(225, 6, 44, 0.14)': 'var(--base-glow)',
    'rgba(30, 136, 255, 0.10)': 'var(--accent-glow)',
    'rgba(15, 23, 42, 0.04)': 'var(--shadow-soft)',
    'rgba(15, 23, 42, 0.5)': 'var(--text-mid-alpha, rgba(15, 23, 42, 0.5))',
    'rgba(15, 157, 88, 0.12)': 'var(--down-glow)',
    'rgba(212, 160, 23, 0.2)': 'var(--warn-glow, rgba(212, 160, 23, 0.2))',
    'rgba(0, 212, 255, 0.05)': 'var(--accent-glow)',
    'rgba(27, 127, 107, 0.12)': 'var(--tech-glow, rgba(27, 127, 107, 0.12))',
    'rgba(27, 127, 107, 0.10)': 'var(--tech-glow, rgba(27, 127, 107, 0.10))',
    'rgba(0, 0, 0, 0.04)': 'var(--shadow-soft)',
    'rgba(0, 0, 0, 0.06)': 'var(--shadow-soft)',
    'rgba(0, 0, 0, 0.08)': 'var(--shadow-soft)',
    'rgba(0, 0, 0, 0.10)': 'var(--shadow-soft)',
    'rgba(0, 0, 0, 0.12)': 'var(--shadow-soft)',
    'rgba(0, 0, 0, 0.18)': 'var(--shadow-soft)',
    'rgba(0, 0, 0, 0.7)': 'var(--modal-overlay, rgba(0, 0, 0, 0.7))',
}


def normalize_hex(value):
    """把颜色值规整成小写、剥空白，便于查表。"""
    return value.strip().lower()


def replace_colors_in_css(content):
    """对一段 CSS 内容做颜色替换。"""
    out = content

    # 颜色匹配：6 位 hex / 3 位 hex / rgba(...) / rgb(...)
    # 每次只处理一种颜色，循环扫描直到没新匹配
    pattern = re.compile(
        r'#(?:[0-9a-fA-F]{3}){1,2}\b'
        r'|rgba?\(\s*[\d.]+\s*,\s*[\d.]+\s*,\s*[\d.]+\s*(?:,\s*[\d.]+\s*)?\)',
        re.IGNORECASE,
    )

    changes = []
    def repl(m):
        original = m.group(0)
        normalized = normalize_hex(original)
        if normalized in COLOR_MAP:
            new = COLOR_MAP[normalized]
            if new != original:
                changes.append((original, new))
                return new
        return original

    new_out = pattern.sub(repl, out)

    # 合并连续相同的 rgba 合并（不做这个，避免破坏）
    return new_out, changes


def main():
    css_dir = Path('src/main/resources/static/css')
    if not css_dir.exists():
        print(f'目录不存在: {css_dir}', file=sys.stderr)
        sys.exit(1)

    # 跳过 skin.css（它本身就是 token 源）
    files = sorted(p for p in css_dir.glob('*.css') if p.name != 'skin.css')

    total_changes = 0
    for f in files:
        content = f.read_text(encoding='utf-8')
        new_content, changes = replace_colors_in_css(content)
        if new_content != content:
            f.write_text(new_content, encoding='utf-8')
            # 统计 unique (original, new) 对
            unique = set(changes)
            print(f'✓ {f.name}: {len(changes)} 处替换（{len(unique)} 种）')
            total_changes += len(changes)

    print(f'\n总计 {total_changes} 处替换')


if __name__ == '__main__':
    main()
