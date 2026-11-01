"""Deterministic, invented chat screenshots. Font is supplied externally, never bundled.

Usage: python scripts/generate_ocr_fixtures.py /path/to/NotoSansCJK-Regular.ttc
Font used for T22: AOSP API35 /system/fonts/NotoSansCJK-Regular.ttc (SIL OFL).
No actual messages, account identifiers, or application screenshots are inputs.
"""
import sys
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "android/app/src/androidTest/assets/ocr"


def generate(font_path):
    OUT.mkdir(parents=True, exist_ok=True)
    font = ImageFont.truetype(font_path, 34)
    small = ImageFont.truetype(font_path, 25)
    for name in ["light_1", "light_2", "dark_1", "dark_2", "long", "group"]:
        dark = name.startswith("dark")
        height = 4200 if name == "long" else 1280
        bg, ink = ("#191919", "#eeeeee") if dark else ("#ededed", "#171717")
        image = Image.new("RGB", (720, height), bg)
        draw = ImageDraw.Draw(image)
        draw.text((32, 15), "09:41", font=small, fill=ink)
        draw.text((250, 70), "合成讨论群" if name == "group" else "合成联系人", font=font, fill=ink)
        draw.line((0, 125, 720, 125), fill="#777777")
        draw.text((270, 155), "今天 09:30", font=small, fill="#888888")
        messages = ["明天一起讨论方案", "好的我先整理材料", "请把重点列出来", "我会提前发给大家", "谢谢你的帮助", "我们一起确认时间"]
        if name.endswith("_2"):
            # Intentional adjacent-image suffix/prefix overlap; later repetitions are real rows.
            messages = messages[-2:] + ["明天一起讨论方案", "我再检查一次细节", "请保留这些问题", "好的我会补充说明"]
        if name == "long":
            messages *= 4
        for row, text in enumerate(messages):
            right = row % 2 == 1
            y = 215 + row * 150
            x = 250 if right else 85
            if name == "group" and not right:
                draw.text((x, y - 30), "小林" if row % 4 == 0 else "小周", font=small, fill="#888888")
            draw.rounded_rectangle((x, y, x + 390, y + 80), 12, fill="#95ec69" if right else ("#2c2c2c" if dark else "#ffffff"))
            draw.text((x + 16, y + 18), text, font=font, fill="#171717" if right else ink)
        draw.rectangle((0, height - 90, 720, height), fill="#303030" if dark else "#f7f7f7")
        draw.text((90, height - 65), "输入消息", font=font, fill="#888888")
        image.save(OUT / f"{name}.png", optimize=False)


if __name__ == "__main__":
    generate(sys.argv[1])
