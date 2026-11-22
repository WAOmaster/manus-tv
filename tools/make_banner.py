# Redraws res/drawable*/banner.png ("MANUS TV"). Run: python tools/make_banner.py (needs Pillow; dev-only, not part of the app)
from PIL import Image, ImageDraw, ImageFont
S = 4  # master 1280x720
W, H = 640 * 2, 360 * 2
INK, PAPER = (5, 5, 5), (244, 241, 234)
logo = Image.open('res/mipmap-xxxhdpi/ic_launcher_foreground.png').convert('RGBA')
logo = logo.crop(logo.getbbox())
lh = 200; lw = round(logo.width * lh / logo.height)
logo = logo.resize((lw, lh), Image.LANCZOS)
font = ImageFont.truetype('C:/Windows/Fonts/arialbd.ttf', 112)
text = 'MANUS TV'
tb = font.getbbox(text); tw, th = tb[2] - tb[0], tb[3] - tb[1]
gap = 44
total = lw + gap + tw
x0 = (W - total) // 2
im = Image.new('RGB', (W, H), INK)
im.paste(logo, (x0, (H - lh) // 2), logo)
ImageDraw.Draw(im).text((x0 + lw + gap - tb[0], (H - th) // 2 - tb[1]), text, font=font, fill=PAPER)
print('content box', x0, x0 + total, 'of', W, '=> margin %.0f%%' % (100 * x0 / W))
for path, (w, h) in {'res/drawable/banner.png': (320, 180), 'res/drawable-xhdpi/banner.png': (320, 180),
                     'res/drawable-xxhdpi/banner.png': (480, 270), 'res/drawable-xxxhdpi/banner.png': (640, 360)}.items():
    im.resize((w, h), Image.LANCZOS).save(path, optimize=True)
