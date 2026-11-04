"""Рисует фоновые картинки для мира (assets/backgrounds/*.png). Всё генерируется кодом, без чужих файлов.
Запуск: python3 tools/make_backgrounds.py"""
import math, random
from PIL import Image, ImageDraw, ImageFilter

W, H = 1440, 600
OUT = "assets/backgrounds/"

def grad(d, top, bot, y0=0, y1=H):
    for y in range(y0, y1):
        t = (y - y0) / max(1, y1 - y0 - 1)
        c = tuple(int(top[k] + (bot[k] - top[k]) * t) for k in range(3))
        d.line([(0, y), (W, y)], fill=c)

def mountains(d, base, amp, color, seed, step=6, rough=0.55):
    rnd = random.Random(seed)
    pts = [(0, H)]
    y = base
    for x in range(0, W + step, step):
        y += rnd.uniform(-amp, amp) * rough
        y = max(base - amp * 3, min(base + amp, y))
        pts.append((x, y + math.sin(x / 140 + seed) * amp))
    pts.append((W, H))
    d.polygon(pts, fill=color)

def clouds(img, seed, n, color, ymax):
    rnd = random.Random(seed)
    layer = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    for _ in range(n):
        cx, cy = rnd.randint(0, W), rnd.randint(20, ymax)
        for _ in range(rnd.randint(5, 9)):
            rx, ry = rnd.randint(40, 110), rnd.randint(18, 40)
            ox, oy = rnd.randint(-90, 90), rnd.randint(-15, 15)
            d.ellipse([cx + ox - rx, cy + oy - ry, cx + ox + rx, cy + oy + ry], fill=color)
    layer = layer.filter(ImageFilter.GaussianBlur(6))
    img.alpha_composite(layer)

def meadow():
    img = Image.new("RGBA", (W, H))
    d = ImageDraw.Draw(img)
    grad(d, (70, 120, 185), (175, 205, 225))
    d.ellipse([1150, 60, 1250, 160], fill=(255, 240, 190))
    sun = Image.new("RGBA", (W, H), (0, 0, 0, 0)); ImageDraw.Draw(sun).ellipse([1110, 20, 1290, 200], fill=(255, 235, 170, 90))
    img.alpha_composite(sun.filter(ImageFilter.GaussianBlur(25)))
    clouds(img, 3, 9, (255, 255, 255, 170), 220)
    d = ImageDraw.Draw(img)
    mountains(d, 360, 40, (120, 150, 170), 1)
    mountains(d, 420, 30, (95, 130, 95), 2)
    mountains(d, 480, 18, (70, 115, 60), 4)
    rnd = random.Random(9)
    for _ in range(28):  # деревья
        x = rnd.randint(0, W); y = rnd.randint(470, 520); h = rnd.randint(40, 90)
        d.rectangle([x - 3, y - h * 0.3, x + 3, y], fill=(70, 50, 35))
        d.ellipse([x - h * 0.35, y - h, x + h * 0.35, y - h * 0.25], fill=(45, 95, 45))
    d.rectangle([0, 520, W, H], fill=(80, 125, 60))
    return img

def night():
    img = Image.new("RGBA", (W, H))
    d = ImageDraw.Draw(img)
    grad(d, (8, 12, 30), (35, 45, 80))
    rnd = random.Random(5)
    for _ in range(500):
        x, y = rnd.randint(0, W), rnd.randint(0, 420); b = rnd.randint(120, 255); r = rnd.choice([0, 0, 0, 1])
        d.ellipse([x - r, y - r, x + r, y + r], fill=(b, b, min(255, b + 20)))
    moon = Image.new("RGBA", (W, H), (0, 0, 0, 0)); md = ImageDraw.Draw(moon)
    md.ellipse([180, 50, 290, 160], fill=(240, 240, 220, 255)); md.ellipse([205, 40, 315, 150], fill=(0, 0, 0, 0))
    img.alpha_composite(moon)
    d = ImageDraw.Draw(img)
    mountains(d, 330, 45, (30, 38, 62), 7, step=10, rough=0.45)
    mountains(d, 420, 35, (20, 26, 45), 11, step=10, rough=0.45)
    mountains(d, 500, 25, (12, 16, 28), 13)
    return img

def lab():
    img = Image.new("RGBA", (W, H))
    d = ImageDraw.Draw(img)
    grad(d, (52, 62, 74), (38, 44, 54))
    for y in range(0, H, 40):  # плитка
        for x in range(0 if (y // 40) % 2 == 0 else -40, W, 80):
            d.rectangle([x + 1, y + 1, x + 79, y + 39], outline=(60, 72, 86))
    # окно
    d.rectangle([980, 60, 1260, 300], fill=(25, 35, 60)); d.rectangle([980, 60, 1260, 300], outline=(95, 100, 110), width=8)
    d.line([(1120, 60), (1120, 300)], fill=(95, 100, 110), width=6); d.line([(980, 180), (1260, 180)], fill=(95, 100, 110), width=6)
    # доска с формулами
    d.rectangle([90, 70, 600, 290], fill=(34, 58, 46)); d.rectangle([90, 70, 600, 290], outline=(110, 85, 60), width=10)
    rnd = random.Random(2)
    for row in range(5):
        x = 120; y = 100 + row * 38
        while x < 560:
            w_ = rnd.randint(14, 40); d.line([(x, y + rnd.randint(-3, 3)), (x + w_, y + rnd.randint(-3, 3))], fill=(190, 200, 190), width=2); x += w_ + rnd.randint(8, 20)
    # полки с колбами
    for sy in (360, 450):
        d.rectangle([700, sy, 1350, sy + 10], fill=(110, 80, 50))
        x = 720
        while x < 1320:
            kind = rnd.randint(0, 2); col = rnd.choice([(80, 160, 210), (200, 90, 80), (120, 190, 110), (210, 190, 90)])
            if kind == 0:
                d.ellipse([x, sy - 40, x + 36, sy], outline=(170, 190, 200), width=2); d.rectangle([x + 13, sy - 62, x + 23, sy - 38], outline=(170, 190, 200), width=2)
                d.chord([x + 2, sy - 38, x + 34, sy - 2], 0, 180, fill=col)
            elif kind == 1:
                d.rectangle([x, sy - 50, x + 22, sy], outline=(170, 190, 200), width=2); d.rectangle([x + 2, sy - 25, x + 20, sy - 2], fill=col)
            else:
                d.polygon([(x, sy), (x + 34, sy), (x + 21, sy - 34), (x + 21, sy - 55), (x + 13, sy - 55), (x + 13, sy - 34)], outline=(170, 190, 200))
                d.polygon([(x + 4, sy - 2), (x + 30, sy - 2), (x + 23, sy - 18), (x + 11, sy - 18)], fill=col)
            x += rnd.randint(50, 80)
    d.rectangle([0, 540, W, H], fill=(60, 50, 44))  # стол
    return img

def kitchen():
    img = Image.new("RGBA", (W, H))
    d = ImageDraw.Draw(img)
    grad(d, (196, 170, 130), (160, 130, 95))
    for y in range(300, 520, 36):
        for x in range(0, W, 36):
            d.rectangle([x + 1, y + 1, x + 35, y + 35], fill=(215, 215, 205), outline=(170, 165, 155))
    for x in range(40, W - 100, 230):  # шкафчики
        d.rectangle([x, 40, x + 200, 230], fill=(140, 95, 60), outline=(90, 60, 40), width=4)
        d.rectangle([x + 95, 120, x + 105, 160], fill=(210, 200, 170))
    d.rectangle([0, 520, W, H], fill=(110, 75, 50))
    return img

for name, f in [("meadow", meadow), ("night", night), ("lab", lab), ("kitchen", kitchen)]:
    im = f().convert("RGB")
    im.save(OUT + name + ".png", optimize=True)
    print(name, im.size)
